package com.betternarrator;

import android.content.Context;
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.k2fsa.sherpa.onnx.*;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Stream native sentence callbacks through bounded queues into one persistent AudioTrack. */
public final class OfflineSpeechEngine implements SpeechEngine {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService generator = Executors.newSingleThreadExecutor();
    private final ExecutorService player = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object audioLock = new Object();
    private final Listener listener;
    private final String modelKind;
    private OfflineTts model;
    private volatile int sampleRate;
    private volatile boolean closed;
    private AudioTrack track;
    private int trackToken = -1, trackCreations;
    private long writtenFrames;
    private Utterance prepared, current;

    static final class Stats {
        final String model;
        final int characters;
        volatile long synthesisStartedAt, firstAudioAt, generationFinishedAt, playbackStartedAt, playbackFinishedAt;
        volatile long totalSamples, returnedSamples;
        volatile int callbacks, underruns;
        Stats(String model, int characters) { this.model = model; this.characters = characters; }
    }
    private final class Utterance {
        final String text;
        final int speaker, token;
        final float rate;
        final PcmQueue audio = new PcmQueue(8);
        final Stats stats;
        volatile boolean cancelled;
        volatile Throwable callbackFailure;
        volatile String id;
        volatile long firstFrame = -1;
        boolean bufferedNotified;
        Utterance(String text, int speaker, float rate, int token) {
            this.text = text; this.speaker = speaker; this.rate = rate; this.token = token;
            stats = new Stats(modelKind, text.length());
        }
        boolean valid() { return !closed && !cancelled && token == generation.get(); }
        boolean matches(String text, int speaker, float rate, int token) {
            return valid() && this.text.equals(text) && this.speaker == speaker && this.rate == rate && this.token == token;
        }
        void cancel() { cancelled = true; audio.clear(); }
    }

    // JNI requires this concrete typed method; an erased Java lambda causes a native abort.
    public final class GenerationCallback implements kotlin.jvm.functions.Function1<float[], Integer> {
        private final Utterance utterance;
        GenerationCallback(Utterance utterance) { this.utterance = utterance; }
        @Override public Integer invoke(float[] samples) {
            if (!utterance.valid()) return 0;
            if (samples == null || samples.length == 0) return 1;
            try {
                if (utterance.stats.firstAudioAt == 0) utterance.stats.firstAudioAt = SystemClock.elapsedRealtime();
                utterance.audio.add(samples, utterance::valid);
                utterance.stats.totalSamples += samples.length;
                utterance.stats.callbacks++;
                return utterance.valid() ? 1 : 0;
            } catch (CancellationException ignored) {
                return 0;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt(); utterance.callbackFailure = error; return 0;
            } catch (RuntimeException error) {
                // Never propagate Java exceptions through the native callback boundary.
                utterance.callbackFailure = error; return 0;
            }
        }
    }

    public OfflineSpeechEngine(Context context, Listener listener) { this(context, "kokoro", listener); }
    public OfflineSpeechEngine(Context context, String modelKind, Listener listener) {
        if (!modelKind.equals("kokoro") && !modelKind.equals("kitten")) throw new IllegalArgumentException("Unknown neural voice: " + modelKind);
        this.listener = listener; this.modelKind = modelKind;
        Context app = context.getApplicationContext();
        generator.execute(() -> {
            long start = SystemClock.elapsedRealtime();
            try {
                model = new OfflineTts(app.getAssets(), modelConfig(modelKind));
                sampleRate = model.sampleRate();
                Log.i("OfflineVoice", modelKind + " loaded in " + (SystemClock.elapsedRealtime() - start) + " ms; speakers=" + model.numSpeakers());
                main.post(() -> { if (!closed) listener.onReady(); });
            } catch (Exception | LinkageError error) {
                error("", "Could not load bundled " + modelKind + ": " + error.getMessage(), generation.get());
            }
        });
    }
    private static OfflineTtsConfig modelConfig(String kind) {
        OfflineTtsModelConfig nativeModel = new OfflineTtsModelConfig();
        if (kind.equals("kitten")) {
            OfflineTtsKittenModelConfig kitten = new OfflineTtsKittenModelConfig();
            kitten.setModel("kitten/model.int8.onnx"); kitten.setVoices("kitten/voices.bin");
            kitten.setTokens("kitten/tokens.txt"); kitten.setLexicon("kitten/lexicon-en-us.txt");
            nativeModel.setKitten(kitten);
        } else {
            OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
            kokoro.setModel("kokoro/model.int8.onnx"); kokoro.setVoices("kokoro/voices.bin");
            kokoro.setTokens("kokoro/tokens.txt"); kokoro.setLexicon("kokoro/lexicon-us-en.txt");
            kokoro.setLang("en-us"); nativeModel.setKokoro(kokoro);
        }
        nativeModel.setNumThreads(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
        nativeModel.setProvider("cpu");
        OfflineTtsConfig config = new OfflineTtsConfig(); config.setModel(nativeModel);
        config.setMaxNumSentences(1);
        return config;
    }
    private Utterance generate(String text, int speaker, float rate, int token) {
        Utterance input = new Utterance(text, speaker, rate, token);
        generator.execute(() -> {
            Throwable failure = null;
            try {
                if (!input.valid()) return;
                if (model == null) throw new IllegalStateException("Bundled voice did not load.");
                input.stats.synthesisStartedAt = SystemClock.elapsedRealtime();
                java.util.List<String> batches = modelKind.equals("kitten") ? SpeechChunks.forSynthesis(text)
                        : java.util.Collections.singletonList(text);
                for (String batch : batches) {
                    if (!input.valid()) return;
                    int callbacksBefore = input.stats.callbacks;
                    GeneratedAudio audio = model.generateWithCallback(batch, speaker, rate, new GenerationCallback(input));
                    if (!input.valid()) return;
                    if (input.callbackFailure != null) throw new IllegalStateException("Audio callback failed", input.callbackFailure);
                    input.stats.returnedSamples += audio.getSamples().length;
                    // Kitten's v2.0.2 backend returns a whole batch and does not invoke the callback.
                    if (input.stats.callbacks == callbacksBefore && audio.getSamples().length > 0)
                        new GenerationCallback(input).invoke(audio.getSamples());
                }
                if (input.stats.totalSamples == 0) throw new IllegalStateException("Voice produced no audio.");
                if (input.stats.totalSamples != input.stats.returnedSamples)
                    throw new IllegalStateException("Streaming sample count differs from generated audio.");
                input.stats.generationFinishedAt = SystemClock.elapsedRealtime();
                notifyBuffered(input);
                double seconds = input.stats.totalSamples / (double) sampleRate;
                long duration = input.stats.generationFinishedAt - input.stats.synthesisStartedAt;
                Log.i("OfflineVoice", String.format(Locale.US,
                        "%s streamed %d chars; first audio=%d ms; generation=%d ms; audio=%.2fs; RTF=%.2f; callbacks=%d",
                        modelKind, text.length(), input.stats.firstAudioAt - input.stats.synthesisStartedAt,
                        duration, seconds, duration / (seconds * 1000), input.stats.callbacks));
            } catch (Exception | LinkageError error) {
                if (input.valid()) failure = error;
            } finally { input.audio.finish(failure); }
        });
        return input;
    }
    @Override public void speak(String text, String id, int speaker, float rate) {
        if (closed) return;
        int token = generation.get();
        Utterance input;
        if (prepared != null && prepared.matches(text, speaker, rate, token)) input = prepared;
        else {
            if (prepared != null) prepared.cancel();
            input = generate(text, speaker, rate, token);
        }
        prepared = null; current = input; input.id = id;
        notifyBuffered(input);
        player.execute(() -> {
            try { play(input, id); }
            catch (CancellationException ignored) { }
            catch (Exception error) {
                if (input.valid()) error(id, "Offline voice failed: " + error.getMessage(), token);
            }
        });
    }
    @Override public void prepare(String text, int speaker, float rate) {
        if (closed) return;
        int token = generation.get();
        if (prepared == null || !prepared.matches(text, speaker, rate, token)) {
            if (prepared != null) prepared.cancel();
            prepared = generate(text, speaker, rate, token);
        }
    }
    private AudioTrack ensureTrack(int token) {
        synchronized (audioLock) {
            if (track != null && trackToken == token) return track;
            releaseTrack();
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            if (minimum <= 0) throw new IllegalStateException("Unsupported offline audio format.");
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, sampleRate / 2 * 4)).build();
            trackToken = token; writtenFrames = 0; trackCreations++;
            track.play(); return track;
        }
    }
    private void play(Utterance input, String id) throws InterruptedException {
        AudioTrack output = null;
        int underrunsBefore = 0;
        float[] samples;
        while ((samples = input.audio.next(input::valid)) != null) {
            if (!input.valid()) throw new CancellationException();
            if (output == null) {
                output = ensureTrack(input.token); underrunsBefore = output.getUnderrunCount();
                input.firstFrame = writtenFrames;
            }
            int offset = 0;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (input.valid() && offset < samples.length) {
                int count = output.write(samples, offset, Math.min(2048, samples.length - offset), AudioTrack.WRITE_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("Audio playback failed: " + count);
                if (count == 0) {
                    if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Audio output stalled.");
                    Thread.sleep(4);
                } else {
                    writtenFrames += count; offset += count; deadline = SystemClock.elapsedRealtime() + 5000;
                    if (input.stats.playbackStartedAt == 0) {
                        input.stats.playbackStartedAt = SystemClock.elapsedRealtime();
                        main.post(() -> { if (input.valid()) listener.onStarted(id); });
                    }
                }
            }
        }
        if (output == null) { if (input.valid()) throw new IllegalStateException("No audio received."); return; }
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (input.valid() && Integer.toUnsignedLong(output.getPlaybackHeadPosition()) < writtenFrames) {
            if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Audio playback stalled.");
            Thread.sleep(10);
        }
        if (input.valid()) {
            input.stats.playbackFinishedAt = SystemClock.elapsedRealtime();
            input.stats.underruns = output.getUnderrunCount() - underrunsBefore;
            main.post(() -> { if (input.valid()) listener.onDone(id); });
        }
    }
    private void releaseTrack() {
        synchronized (audioLock) {
            if (track != null) { track.release(); track = null; }
            trackToken = -1; writtenFrames = 0;
        }
    }
    private void notifyBuffered(Utterance input) {
        main.post(() -> {
            if (!input.valid() || current != input || input.id == null || input.bufferedNotified || input.stats.generationFinishedAt == 0) return;
            long played = 0;
            synchronized (audioLock) {
                if (track != null && trackToken == input.token && input.firstFrame >= 0)
                    played = Math.max(0, Integer.toUnsignedLong(track.getPlaybackHeadPosition()) - input.firstFrame);
            }
            long remaining = Math.max(0, input.stats.totalSamples - played) * 1000 / sampleRate;
            input.bufferedNotified = true;
            listener.onBuffered(input.id, remaining);
        });
    }
    Stats lastStats() { return current == null ? null : current.stats; }
    int audioTrackCreations() { return trackCreations; }
    private void error(String id, String message, int token) {
        Log.e("OfflineVoice", message);
        main.post(() -> { if (!closed && token == generation.get()) listener.onError(id, message); });
    }
    @Override public void stop() {
        generation.incrementAndGet();
        if (current != null) current.cancel();
        if (prepared != null) { prepared.cancel(); prepared = null; }
        synchronized (audioLock) {
            if (track != null) { try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { } }
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; stop();
        player.execute(this::releaseTrack); player.shutdown();
        generator.execute(() -> { if (model != null) { model.release(); model = null; } }); generator.shutdown();
    }
}

package com.betternarrator;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Same paragraph, threads, and playback pipeline for each model. Includes cold and prefetched input. */
@RunWith(AndroidJUnit4.class)
public class StreamingVoiceBenchmarkTest {
    private static final String TEXT = "The ship moved slowly across the quiet harbor. Above the water, the morning sky was clear. "
            + "A bell rang from the distant tower. The travelers gathered their bags and waited for the captain.";

    @Test public void compareModelsAndVerifyIncrementalPlayback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONArray measurements = new JSONArray();
        for (String kind : new String[]{"kokoro", "kitten"}) {
            CountDownLatch ready = new CountDownLatch(1);
            BlockingQueue<String> done = new LinkedBlockingQueue<>();
            AtomicReference<String> error = new AtomicReference<>();
            AtomicReference<OfflineSpeechEngine> engine = new AtomicReference<>();
            java.util.Map<String, Long> buffered = new ConcurrentHashMap<>();
            long loadStart = SystemClock.elapsedRealtime();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> engine.set(new OfflineSpeechEngine(context, kind,
                    new SpeechEngine.Listener() {
                        @Override public void onReady() { ready.countDown(); }
                        @Override public void onStarted(String id) { }
                        @Override public void onDone(String id) { done.add(id); }
                        @Override public void onBuffered(String id, long remainingMillis) { buffered.put(id, remainingMillis); }
                        @Override public void onError(String id, String message) { error.set(message); ready.countDown(); done.add("error"); }
                    })));
            try {
                assertTrue("Model load timed out: " + kind, ready.await(90, TimeUnit.SECONDS));
                assertNull(error.get());
                long loadMs = SystemClock.elapsedRealtime() - loadStart;
                int speaker = kind.equals("kitten") ? 1 : 3;
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                    engine.get().speak(TEXT, "first", speaker, 1f);
                    engine.get().prepare(TEXT, speaker, 1f);
                });
                assertEquals("First paragraph timed out", "first", done.poll(120, TimeUnit.SECONDS));
                assertNull(error.get());
                OfflineSpeechEngine.Stats first = engine.get().lastStats();
                assertStreaming(first);
                assertTrue("Current passage should report buffered audio", buffered.containsKey("first"));
                assertFalse("Prefetch must not notify as current playback", buffered.containsKey("prefetched"));
                assertTrue(buffered.get("first") > 0);
                measurements.put(row(first, "first", loadMs));
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> engine.get().speak(TEXT, "prefetched", speaker, 1f));
                assertEquals("Prefetched paragraph timed out", "prefetched", done.poll(120, TimeUnit.SECONDS));
                assertNull(error.get());
                assertTrue("A fully prefetched passage must notify when it becomes current", buffered.containsKey("prefetched"));
                OfflineSpeechEngine.Stats second = engine.get().lastStats();
                assertTrue(second.callbacks > 1);
                assertEquals(second.totalSamples, second.returnedSamples);
                measurements.put(row(second, "prefetched", loadMs));
                assertEquals("Audio output must persist across paragraph boundaries", 1, engine.get().audioTrackCreations());
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> engine.get().close());
            }
        }
        JSONObject report = new JSONObject().put("device", android.os.Build.MODEL)
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("text", TEXT)
                .put("threads", Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)))
                .put("results", measurements);
        File file = new File(context.getExternalFilesDir(null), "offline-voice-benchmark.json");
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
        Log.i("VoiceBenchmark", report.toString());
    }
    private static void assertStreaming(OfflineSpeechEngine.Stats stats) {
        assertTrue("Need multiple native audio callbacks", stats.callbacks > 1);
        assertEquals("Do not duplicate or discard callback samples", stats.returnedSamples, stats.totalSamples);
        assertTrue("Audio playback must start before full synthesis finishes", stats.playbackStartedAt < stats.generationFinishedAt);
        assertTrue(stats.firstAudioAt < stats.generationFinishedAt);
        assertTrue("Done must wait for audio to drain", stats.playbackFinishedAt >= stats.generationFinishedAt);
    }
    private static JSONObject row(OfflineSpeechEngine.Stats stats, String pass, long loadMs) throws Exception {
        long generationMs = stats.generationFinishedAt - stats.synthesisStartedAt;
        double audioSeconds = stats.totalSamples / 24000.0;
        return new JSONObject().put("model", stats.model).put("pass", pass).put("load_ms", loadMs)
                .put("delivery", stats.model.equals("kitten") ? "bounded native batches into continuous PCM" : "native callbacks into continuous PCM")
                .put("characters", stats.characters).put("first_audio_ms", stats.firstAudioAt - stats.synthesisStartedAt)
                .put("generation_ms", generationMs).put("audio_seconds", audioSeconds)
                .put("rtf", generationMs / (audioSeconds * 1000)).put("callbacks", stats.callbacks)
                .put("playback_start_ms", stats.playbackStartedAt - stats.synthesisStartedAt)
                .put("playback_end_ms", stats.playbackFinishedAt - stats.synthesisStartedAt)
                .put("underruns", stats.underruns);
    }
}

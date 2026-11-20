package com.betternarrator;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import java.util.Comparator;
import java.util.Locale;

/** Explicit fallback: refuse network voices rather than silently contacting a TTS service. */
public final class SystemSpeechEngine implements SpeechEngine {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final TextToSpeech tts;
    private boolean closed, ready;
    public SystemSpeechEngine(Context context, Listener listener) {
        this.listener = listener;
        tts = new TextToSpeech(context.getApplicationContext(), result -> main.post(() -> initialize(result)));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(() -> { if (!closed) listener.onStarted(id); }); }
            @Override public void onDone(String id) { main.post(() -> { if (!closed) listener.onDone(id); }); }
            @Override public void onError(String id) { main.post(() -> { if (!closed) listener.onError(id, "The installed offline voice failed."); }); }
        });
    }
    private void initialize(int result) {
        if (closed) return;
        if (result == TextToSpeech.SUCCESS && tts.getVoices() != null) {
            Voice voice = tts.getVoices().stream()
                    .filter(v -> v.getLocale().getLanguage().equals("en") && !v.isNetworkConnectionRequired()
                            && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
                    .sorted(Comparator.comparing((Voice v) -> !v.getLocale().equals(Locale.US))
                            .thenComparing(Voice::getQuality, Comparator.reverseOrder()).thenComparing(Voice::getName))
                    .findFirst().orElse(null);
            ready = voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS;
        }
        if (ready) listener.onReady();
        else listener.onError("", "No installed offline English voice. Choose Kokoro, or install an offline Android voice.");
    }
    @Override public void speak(String text, String id, int speaker, float rate) {
        if (!ready || closed) { listener.onError(id, "Offline Android voice is not ready."); return; }
        tts.setSpeechRate(rate);
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR)
            listener.onError(id, "Could not start the installed offline voice.");
    }
    @Override public void stop() { tts.stop(); }
    @Override public void close() { closed = true; tts.stop(); tts.shutdown(); }
}

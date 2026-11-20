package com.betternarrator;

/** Every engine calls listeners on the main thread. Playback completion drives page advances. */
public interface SpeechEngine extends AutoCloseable {
    interface Listener {
        void onReady();
        void onStarted(String id);
        void onDone(String id);
        void onError(String id, String message);
        /** Current passage is fully generated; remaining audio can cover capture/generation ahead. */
        default void onBuffered(String id, long remainingMillis) { }
    }
    void speak(String text, String id, int speaker, float rate);
    default void prepare(String text, int speaker, float rate) { }
    void stop();
    @Override void close();
}

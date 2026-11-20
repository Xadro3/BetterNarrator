package com.betternarrator;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Exercises the bundled native model and real AudioTrack; no network permission or mock audio. */
@RunWith(AndroidJUnit4.class)
public class OfflineSpeechEngineTest {
    @Test public void bundledVoicePlaysOfflineAndPauseCancelsPrefetch() throws Exception {
        checkPause("kokoro", 3, 16);
    }
    @Test public void kittenPauseCancelsStreamingAndPrefetch() throws Exception {
        checkPause("kitten", 1, 0);
    }
    private void checkPause(String model, int firstSpeaker, int secondSpeaker) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("Narration APK must not have network access", PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(Manifest.permission.INTERNET));
        CountDownLatch ready = new CountDownLatch(1), started = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<SpeechEngine> engine = new AtomicReference<>();
        AtomicLong playbackStart = new AtomicLong(), playbackEnd = new AtomicLong();
        List<String> completions = new CopyOnWriteArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> engine.set(new OfflineSpeechEngine(context, model,
                new SpeechEngine.Listener() {
                    @Override public void onReady() { ready.countDown(); }
                    @Override public void onStarted(String id) {
                        if (id.equals("cancel")) started.countDown();
                        if (id.equals("finish")) playbackStart.set(SystemClock.elapsedRealtime());
                    }
                    @Override public void onDone(String id) {
                        completions.add(id);
                        if (id.equals("finish")) { playbackEnd.set(SystemClock.elapsedRealtime()); finished.countDown(); }
                    }
                    @Override public void onError(String id, String message) {
                        error.set(message); ready.countDown(); started.countDown(); finished.countDown();
                    }
                })));
        try {
            assertTrue("Native model initialization timed out", ready.await(90, TimeUnit.SECONDS));
            assertNull(error.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                engine.get().speak("The ship drifted beneath the endless blue sky. A new adventure was waiting beyond the clouds.", "cancel", firstSpeaker, 1f);
                engine.get().prepare("This prefetched sentence must not play after pause.", firstSpeaker, 1f);
            });
            assertTrue("First playback did not start", started.await(90, TimeUnit.SECONDS));
            assertNull(error.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                engine.get().stop();
                engine.get().speak("Beyond the mountain, the sky was clear.", "finish", secondSpeaker, 1f);
            });
            assertTrue("Resumed playback did not finish", finished.await(90, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(java.util.Collections.singletonList("finish"), completions);
            assertTrue("Completion must follow audible playback, not just synthesis", playbackEnd.get() - playbackStart.get() > 500);
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> engine.get().close());
        }
    }
}

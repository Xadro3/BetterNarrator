package com.betternarrator;

import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class PcmQueueTest {
    @Test public void completionDrainsAllQueuedAudioInOrder() throws Exception {
        PcmQueue queue = new PcmQueue(2);
        queue.add(new float[]{1, 2}, () -> true); queue.add(new float[]{3}, () -> true); queue.finish(null);
        assertArrayEquals(new float[]{1, 2}, queue.next(() -> true), 0);
        assertArrayEquals(new float[]{3}, queue.next(() -> true), 0);
        assertNull(queue.next(() -> true));
    }
    @Test public void fullBufferCanBeCancelledWithoutDeadlock() throws Exception {
        PcmQueue queue = new PcmQueue(1); queue.add(new float[]{1}, () -> true);
        AtomicBoolean valid = new AtomicBoolean(true);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> blocked = worker.submit(() -> {
                try { queue.add(new float[]{2}, valid::get); fail("Cancelled producer must stop"); }
                catch (CancellationException expected) { }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            });
            valid.set(false);
            blocked.get(1, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }
    @Test public void failureAfterPartialAudioIsSurfaced() throws Exception {
        PcmQueue queue = new PcmQueue(1); queue.add(new float[]{1}, () -> true);
        queue.finish(new IllegalArgumentException("bad voice"));
        assertNotNull(queue.next(() -> true));
        try { queue.next(() -> true); fail("Failure must not be reported as successful completion"); }
        catch (IllegalStateException expected) { assertEquals("bad voice", expected.getCause().getMessage()); }
    }
}

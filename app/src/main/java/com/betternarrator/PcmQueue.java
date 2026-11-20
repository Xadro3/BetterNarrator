package com.betternarrator;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded producer/consumer queue. Completion never needs a slot in the audio queue. */
final class PcmQueue {
    private final ArrayBlockingQueue<float[]> audio;
    private volatile boolean finished;
    private volatile Throwable failure;
    PcmQueue(int capacity) { audio = new ArrayBlockingQueue<>(capacity); }
    void add(float[] samples, BooleanSupplier valid) throws InterruptedException {
        while (valid.getAsBoolean()) {
            if (audio.offer(samples, 50, TimeUnit.MILLISECONDS)) return;
        }
        throw new CancellationException();
    }
    float[] next(BooleanSupplier valid) throws InterruptedException {
        while (valid.getAsBoolean()) {
            float[] samples = audio.poll(50, TimeUnit.MILLISECONDS);
            if (samples != null) return samples;
            // Check finished before isEmpty: a producer cannot enqueue after publishing finished.
            if (finished && audio.isEmpty()) {
                if (failure != null) throw new IllegalStateException("Synthesis failed", failure);
                return null;
            }
        }
        throw new CancellationException();
    }
    void finish(Throwable error) { failure = error; finished = true; }
    void clear() { audio.clear(); }
}

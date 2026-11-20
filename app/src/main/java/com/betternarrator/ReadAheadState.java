package com.betternarrator;

import java.util.Collections;
import java.util.List;

/** One screen of lookahead; preserve navigation attempts across pause to avoid double scrolling. */
final class ReadAheadState {
    enum Phase { IDLE, LOADING, READY, FAILED }
    private Phase phase = Phase.IDLE;
    private boolean moved;
    private long settledAt;
    private String screen = "", error = "";
    private List<String> chunks = Collections.emptyList();
    Phase phase() { return phase; }
    boolean moved() { return moved; }
    String error() { return error; }
    boolean begin() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.LOADING; return true;
    }
    void navigationAttempted() { navigationAttempted(0); }
    void navigationAttempted(long settledAt) { moved = true; this.settledAt = settledAt; }
    long captureDelay(long now) { return Math.max(0, settledAt - now); }
    boolean needsCapture() { return phase == Phase.LOADING && moved; }
    void ready(String screen, List<String> chunks) {
        if (phase != Phase.LOADING || chunks.isEmpty()) throw new IllegalStateException("No pending screen to fill");
        this.screen = screen; this.chunks = Collections.unmodifiableList(new java.util.ArrayList<>(chunks)); phase = Phase.READY;
    }
    void fail(String message) { error = message; phase = Phase.FAILED; }
    String screen() { return screen; }
    List<String> chunks() { return chunks; }
    void reset() { phase = Phase.IDLE; moved = false; settledAt = 0; screen = ""; error = ""; chunks = Collections.emptyList(); }
    static boolean canPrepareEarly(boolean automatic, boolean enabled, boolean neural, boolean lastChunk, long remainingMs) {
        return automatic && enabled && neural && lastChunk && remainingMs >= 8000;
    }
}

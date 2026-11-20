package com.betternarrator;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class ReadAheadStateTest {
    @Test public void onlyOneScreenCanBeRequestedAtATime() {
        ReadAheadState state = new ReadAheadState();
        assertTrue(state.begin()); assertFalse(state.begin());
        state.navigationAttempted();
        state.ready("screen two", Arrays.asList("first passage", "second passage"));
        assertFalse(state.begin()); assertEquals(ReadAheadState.Phase.READY, state.phase());
        assertEquals("first passage", state.chunks().get(0));
    }
    @Test public void pauseDuringCaptureRetainsMovementForRecaptureWithoutAnotherScroll() {
        ReadAheadState state = new ReadAheadState(); state.begin(); state.navigationAttempted();
        assertTrue(state.needsCapture()); assertFalse(state.begin());
        // Epoch cancellation belongs to the service; state must retain the attempted movement.
        assertTrue(state.moved());
        state.ready("same visible screen", Arrays.asList("continuation"));
        assertFalse(state.needsCapture()); assertTrue(state.moved());
    }
    @Test public void failureRetainsMovementUntilExplicitRecovery() {
        ReadAheadState state = new ReadAheadState(); state.begin(); state.navigationAttempted(); state.fail("No overlap");
        assertEquals("No overlap", state.error()); assertTrue(state.moved()); assertFalse(state.begin());
    }
    @Test public void aQuickPauseResumeStillWaitsForTheOriginalGestureToSettle() {
        ReadAheadState state = new ReadAheadState(); state.begin(); state.navigationAttempted(2600);
        assertEquals(1400, state.captureDelay(1200));
        assertTrue(state.needsCapture()); assertFalse(state.begin());
        assertEquals(0, state.captureDelay(2700));
        state.reset(); assertEquals(0, state.captureDelay(0));
    }
    @Test public void promotingThenResettingAllowsExactlyOneFollowingScreen() {
        ReadAheadState state = new ReadAheadState(); state.begin(); state.navigationAttempted();
        state.ready("visible text", Arrays.asList("next audio"));
        assertEquals("visible text", state.screen()); assertEquals("next audio", state.chunks().get(0));
        state.reset(); assertFalse(state.moved()); assertTrue(state.chunks().isEmpty()); assertTrue(state.begin());
    }
    @Test public void earlyMovementRequiresEnoughBufferedAudioAndOptIn() {
        assertTrue(ReadAheadState.canPrepareEarly(true, true, true, true, 8000));
        assertFalse(ReadAheadState.canPrepareEarly(true, true, true, true, 7999));
        assertFalse(ReadAheadState.canPrepareEarly(false, true, true, true, 30000));
        assertFalse(ReadAheadState.canPrepareEarly(true, false, true, true, 30000));
        assertFalse(ReadAheadState.canPrepareEarly(true, true, false, true, 30000));
        assertFalse(ReadAheadState.canPrepareEarly(true, true, true, false, 30000));
    }
}

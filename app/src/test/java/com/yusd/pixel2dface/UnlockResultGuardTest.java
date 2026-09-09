package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UnlockResultGuardTest {
    @Test public void resultIsSingleUseWhileHostRemainsAlive() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L);
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.consume("session-a", 210L, 1L));
        assertTrue(gate.mayDeliver("session-a", 210L, 1L));
    }

    @Test public void expiredOrClockReversedResultsFailClosed() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L);
        assertFalse(gate.consume("session-a", 99L, 1L));
        assertFalse(gate.consume("session-a", 101L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L));
    }

    @Test public void sleepOrNewWakeCancelsQueuedSuccess() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L);
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.mayDeliver("session-a", 201L, 2L));
        gate.clear();
        assertFalse(gate.mayDeliver("session-a", 201L, 1L));
        gate.start("session-b", 250L, 2L);
        assertFalse(gate.consume("session-a", 300L, 2L));
        assertTrue(gate.consume("session-b", 300L, 2L));
    }

    @Test public void wrongTokenDoesNotConsumeLegitimateResult() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L);
        assertFalse(gate.mayDeliver("session-a", 101L, 1L));
        assertFalse(gate.consume(null, 101L, 1L));
        assertFalse(gate.consume("wrong", 101L, 1L));
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.mayDeliver("session-a", 201L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L));
    }
}

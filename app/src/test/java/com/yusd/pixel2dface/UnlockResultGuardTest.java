package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class UnlockResultGuardTest {
    @Test public void resultIsSingleUseWhileHostRemainsAlive() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.consume("session-a", 210L, 1L));
        assertTrue(gate.mayDeliver("session-a", 210L, 1L, 0));
    }

    @Test public void expiredOrClockReversedResultsFailClosed() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        assertFalse(gate.consume("session-a", 99L, 1L));
        assertFalse(gate.consume("session-a", 101L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L));
    }

    @Test public void sleepOrNewWakeCancelsQueuedSuccess() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.mayDeliver("session-a", 201L, 2L, 0));
        gate.clear();
        assertFalse(gate.mayDeliver("session-a", 201L, 1L, 0));
        gate.start("session-b", 250L, 2L, 0);
        assertFalse(gate.consume("session-a", 300L, 2L));
        assertTrue(gate.consume("session-b", 300L, 2L));
    }

    @Test public void wrongTokenDoesNotConsumeLegitimateResult() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        assertFalse(gate.mayDeliver("session-a", 101L, 1L, 0));
        assertFalse(gate.consume(null, 101L, 1L));
        assertFalse(gate.consume("wrong", 101L, 1L));
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.mayDeliver("session-a", 201L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L, 0));
    }

    @Test public void deliveryIsSingleUseAndBoundToTheLaunchUser() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 10);
        assertTrue(gate.consume("session-a", 200L, 1L));
        assertFalse(gate.claimDelivery("session-a", 201L, 1L, 0));
        assertTrue(gate.claimDelivery("session-a", 202L, 1L, 10));
        assertFalse(gate.claimDelivery("session-a", 203L, 1L, 10));
        assertFalse(gate.mayDeliver("session-a", 204L, 1L, 10));
    }

    @Test public void invalidNewSessionCannotKeepAnOldCapabilityAlive() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        gate.start("", 200L, 2L, 0);
        assertFalse(gate.consume("session-a", 201L, 1L));
        assertFalse(gate.consume("", 201L, 2L));
        gate.start("session-b", -1L, 2L, 0);
        assertFalse(gate.consume("session-b", 201L, 2L));
        gate.start("session-c", 300L, 3L, -1);
        assertFalse(gate.consume("session-c", 301L, 3L));
    }

    @Test public void exactExpiryBoundaryIsAcceptedButTheNextMillisecondIsRejected() {
        UnlockResultGuard gate = new UnlockResultGuard();
        gate.start("session-a", 100L, 1L, 0);
        assertTrue(gate.consume("session-a", 100L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L));
        assertFalse(gate.claimDelivery("session-a",
                101L + UnlockResultGuard.MAX_RESULT_AGE_MS, 1L, 0));
    }
}

package com.yusd.pixel2dface;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CaptureSessionGateTest {
    @Test public void closeDuringInferenceWaitsForItsFrame() {
        CaptureSessionGate gate = new CaptureSessionGate();
        assertTrue(gate.acquireFrame());
        assertFalse(gate.acquireFrame());
        gate.requestClose();
        assertFalse(gate.acquireFrame());
        assertFalse(gate.claimResourceRelease());
        gate.completeFrame();
        assertTrue(gate.claimResourceRelease());
        assertFalse(gate.claimResourceRelease());
        assertFalse(gate.acquireFrame());
    }

    @Test public void idleCloseReleasesOnlyOnce() {
        CaptureSessionGate gate = new CaptureSessionGate();
        assertFalse(gate.claimResourceRelease());
        gate.requestClose();
        gate.requestClose();
        assertTrue(gate.claimResourceRelease());
        assertFalse(gate.claimResourceRelease());
    }

    @Test public void oldSessionCannotInvalidateNewSession() {
        CaptureSessionGate old = new CaptureSessionGate();
        CaptureSessionGate next = new CaptureSessionGate();
        old.acquireFrame();
        next.acquireFrame();
        old.requestClose();
        old.completeFrame();
        assertTrue(old.claimResourceRelease());
        assertFalse(next.claimResourceRelease());
        next.completeFrame();
        assertTrue(next.acquireFrame());
    }
}

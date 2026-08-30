package com.yusd.pixel2dface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PassiveEyeGateTest {
    @Test
    public void closedProbabilitiesCannotBeOverriddenByContours() {
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.00f, 0.01f, 0.20f, 0.18f));
    }

    @Test
    public void oneClosedEyeRejectsTheFrame() {
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.99f, 0.24f, 0.22f, 0.18f));
    }

    @Test
    public void missingClassificationRejectsTheFrame() {
        assertFalse(PassiveEyeGate.areBothEyesOpen(null, 0.99f, 0.22f, 0.20f));
    }

    @Test
    public void naturallyOpenEyesPass() {
        assertTrue(PassiveEyeGate.areBothEyesOpen(0.91f, 0.88f, 0.18f, 0.17f));
    }
}

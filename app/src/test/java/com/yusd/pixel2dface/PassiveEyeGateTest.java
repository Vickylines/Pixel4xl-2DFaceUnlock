package com.yusd.pixel2dface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

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

    @Test public void ambiguousEyesCannotUseOldPermissiveCutoff() {
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.69f, 0.99f, 0.22f, 0.20f));
        assertTrue(PassiveEyeGate.areBothEyesOpen(0.70f, 0.70f, 0.18f, 0.17f));
        assertFalse(PassiveEyeGate.areConfidentlyOpen(0.84f, 0.99f, 0.18f, 0.17f));
        assertTrue(PassiveEyeGate.areConfidentlyOpen(0.90f, 0.99f, 0.18f, 0.17f));
    }

    @Test public void invalidSignalsNeverCountAsOpen() {
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1f, 1.01f}) {
            assertFalse(PassiveEyeGate.areBothEyesOpen(value, 0.99f, 0.2f, 0.2f));
            assertFalse(PassiveEyeGate.areBothEyesOpen(0.99f, value, 0.2f, 0.2f));
        }
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.99f, 0.99f, Float.POSITIVE_INFINITY, 0.2f));
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.99f, 0.99f, 0.2f, Float.NaN));
        assertFalse(PassiveEyeGate.areBothEyesOpen(0.99f, 0.99f, 1.01f, 0.2f));
    }

    @Test public void unavailableClassificationIsDistinctFromAClosedEye() {
        assertEquals(PassiveEyeGate.Problem.CLASSIFICATION_UNAVAILABLE,
                PassiveEyeGate.problem(null, 0.99f, 0.2f, 0.2f));
        assertEquals(PassiveEyeGate.Problem.CLASSIFICATION_UNAVAILABLE,
                PassiveEyeGate.problem(0.99f, Float.NaN, 0.2f, 0.2f));
    }

    @Test public void lowProbabilityRetainsPriorityOverAnUnreliableContour() {
        assertEquals(PassiveEyeGate.Problem.PROBABILITY_REJECTED,
                PassiveEyeGate.problem(0.69f, 0.99f, 0f, 0f));
        assertEquals(PassiveEyeGate.Problem.PROBABILITY_REJECTED,
                PassiveEyeGate.problem(0.99f, 0.69f, 0.2f, 0.2f));
    }

    @Test public void goodClassificationStillRequiresBothValidContours() {
        assertEquals(PassiveEyeGate.Problem.CONTOUR_UNRELIABLE,
                PassiveEyeGate.problem(0.99f, 0.99f, 0.09f, 0.2f));
        assertEquals(PassiveEyeGate.Problem.CONTOUR_UNRELIABLE,
                PassiveEyeGate.problem(0.99f, 0.99f, 0.2f, Float.NaN));
        assertEquals(PassiveEyeGate.Problem.NONE,
                PassiveEyeGate.problem(0.70f, 0.70f, 0.10f, 0.10f));
    }
}

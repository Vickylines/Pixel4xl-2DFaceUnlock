package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import java.util.Properties;
import org.junit.Test;

public final class StorePolicyTest {
    @Test public void fiveFailuresLockOutAndFurtherFailuresCannotShortenIt() {
        Properties properties = new Properties();
        for (int i = 0; i < 4; i++) {
            assertTrue(StorePolicy.recordFailure(properties, 100_000L));
            assertEquals(0L, StorePolicy.lockoutUntil(properties));
        }
        assertTrue(StorePolicy.recordFailure(properties, 100_000L));
        assertEquals(130_000L, StorePolicy.lockoutUntil(properties));
        assertTrue(StorePolicy.recordFailure(properties, 100_001L));
        assertEquals(130_000L, StorePolicy.lockoutUntil(properties));
        assertEquals("0", properties.getProperty("failures"));
        assertTrue(StorePolicy.recordFailure(properties, 130_001L));
        assertEquals(0L, StorePolicy.lockoutUntil(properties));
        assertEquals("1", properties.getProperty("failures"));
    }

    @Test public void malformedSecurityFieldsFailClosedAndAreNotAutomaticallyRepaired() {
        for (String key : new String[]{"failures", "lockout_until"}) {
            for (String value : new String[]{"garbage", "-1", "9223372036854775808"}) {
                Properties properties = new Properties();
                properties.setProperty(key, value);
                assertFalse(StorePolicy.validFailureState(properties));
                assertEquals(Long.MAX_VALUE, StorePolicy.lockoutUntil(properties));
                assertFalse(StorePolicy.recordFailure(properties, 1000L));
                assertEquals(value, properties.getProperty(key));
            }
        }
        for (String failures : new String[]{"5", "2147483647", "2147483648"}) {
            Properties properties = new Properties();
            properties.setProperty("failures", failures);
            assertEquals(Long.MAX_VALUE, StorePolicy.lockoutUntil(properties));
        }
    }

    @Test public void profileNeverRaisesThresholdAboveCalibratedOrOriginalCaps() {
        Properties properties = new Properties();
        properties.setProperty("calibrated_threshold", "0.35");
        properties.setProperty("recognition_profile", "0");
        assertEquals(0.30f, StorePolicy.recognitionThreshold(properties), 0f);
        properties.setProperty("recognition_profile", "1");
        assertEquals(0.33f, StorePolicy.recognitionThreshold(properties), 0f);
        properties.setProperty("recognition_profile", "2");
        assertEquals(0.35f, StorePolicy.recognitionThreshold(properties), 0f);
        properties.setProperty("calibrated_threshold", "0.28");
        assertEquals(0.28f, StorePolicy.recognitionThreshold(properties), 0f);
        properties.setProperty("recognition_profile", "unknown");
        assertEquals(1, StorePolicy.profile(properties));
    }

    @Test public void malformedFloatCannotEscapeAsNaNOrInfinity() {
        for (String value : new String[]{"NaN", "Infinity", "-Infinity", "oops"}) {
            Properties properties = new Properties();
            properties.setProperty("calibrated_threshold", value);
            assertEquals(0.33f, StorePolicy.recognitionThreshold(properties), 0f);
        }
    }
}

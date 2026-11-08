package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ProviderCallerPolicyTest {
    @Test public void onlyOwnUidOrAuthenticatedPlatformSystemUiUidCanAccessState() {
        assertTrue(ProviderCallerPolicy.allowed(10_123, 10_123, 1000, false, false));
        assertTrue(ProviderCallerPolicy.allowed(1000, 10_123, 1000, true, true));
        assertFalse(ProviderCallerPolicy.allowed(10_124, 10_123, 1000, true, true));
        assertFalse(ProviderCallerPolicy.allowed(1000, 10_123, 1000, true, false));
        assertFalse(ProviderCallerPolicy.allowed(1000, 10_123, 1000, false, true));
        assertFalse(ProviderCallerPolicy.allowed(-1, 10_123, -1, true, true));
    }
}

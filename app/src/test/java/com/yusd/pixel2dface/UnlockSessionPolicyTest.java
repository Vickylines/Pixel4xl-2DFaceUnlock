package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static com.yusd.pixel2dface.UnlockSessionPolicy.Decision.*;

import org.junit.Test;

public final class UnlockSessionPolicyTest {
    private static final String TOKEN = "synthetic-session-token-00000000000001";
    private static final String WRONG = "synthetic-session-token-00000000000002";

    @Test public void freshGrantConsumesAndWrongGuessPreservesIt() {
        String record = UnlockSessionPolicy.create(TOKEN, 100_000L, 20_000L, 3);
        assertEquals(CONSUME, UnlockSessionPolicy.inspect(record, TOKEN, 115_000L, 35_000L, 3));
        assertEquals(KEEP_REJECT, UnlockSessionPolicy.inspect(record, WRONG, 100_500L, 20_500L, 3));
        assertEquals(KEEP_REJECT, UnlockSessionPolicy.inspect(record, "bad", 100_500L, 20_500L, 3));
    }

    @Test public void expiredClockRolledBackOrRebootedGrantIsDiscarded() {
        String record = UnlockSessionPolicy.create(TOKEN, 100_000L, 20_000L, 3);
        assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 115_001L, 35_001L, 3));
        assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 97_999L, 20_500L, 3));
        assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 100_500L, 19_999L, 3));
        assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 100_500L, 20_500L, 4));
        assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 100_500L, 20_500L, -1));
    }

    @Test public void missingOldOversizedAndMalformedRecordsFailClosed() {
        assertEquals(KEEP_REJECT, UnlockSessionPolicy.inspect(null, TOKEN, 1000L, 10L, 1));
        for (String record : new String[]{"", TOKEN + "\n1000\n10", "A".repeat(513),
                "2\n" + TOKEN + "\nNaN\n10\n1",
                "2\n" + TOKEN + "\n1000\n10\n1\nextra"}) {
            assertEquals(DISCARD_REJECT, UnlockSessionPolicy.inspect(record, TOKEN, 1000L, 10L, 1));
        }
    }

    @Test public void tokenCannotInjectFieldsOrUnboundedData() {
        assertTrue(UnlockSessionPolicy.validToken("4fe2dbb5-ef39-4d3e-b94a-fce06c14b3d8"));
        for (String value : new String[]{"short", "A".repeat(161), TOKEN + "\n", TOKEN + "\r",
                TOKEN + "\u0000", TOKEN + "汉", TOKEN + " "}) {
            assertFalse(UnlockSessionPolicy.validToken(value));
            assertThrows(IllegalArgumentException.class,
                    () -> UnlockSessionPolicy.create(value, 1000L, 10L, 1));
        }
    }
}

package com.yusd.pixel2dface;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Pure policy for bounded, boot-bound, single-use launch grants. */
final class UnlockSessionPolicy {
    static final int MAX_RECORD_BYTES = 512;
    static final long VALID_MS = 15_000L;
    enum Decision { KEEP_REJECT, DISCARD_REJECT, CONSUME }

    private UnlockSessionPolicy() { }

    static boolean validToken(String token) {
        if (token == null || token.length() < 32 || token.length() > 160) return false;
        for (int i = 0; i < token.length(); i++) {
            char value = token.charAt(i);
            if (!((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9') || value == '-' || value == '_'
                    || value == '.')) return false;
        }
        return true;
    }

    static String create(String token, long wall, long elapsed, int boot) {
        if (!validToken(token) || wall <= 0L || elapsed < 0L || boot < 0) {
            throw new IllegalArgumentException("Invalid launch grant");
        }
        return "2\n" + token + "\n" + wall + "\n" + elapsed + "\n" + boot;
    }

    static Decision inspect(String record, String suppliedToken, long wallNow,
            long elapsedNow, int currentBoot) {
        if (!validToken(suppliedToken)) return Decision.KEEP_REJECT;
        if (record == null) return Decision.KEEP_REJECT;
        if (record.length() > MAX_RECORD_BYTES) return Decision.DISCARD_REJECT;
        String[] parts = record.split("\n", -1);
        // Old transient three-line grants deliberately expire on upgrade. Enrollment
        // compatibility is independent of launch authorization.
        if (parts.length != 5 || !"2".equals(parts[0]) || !validToken(parts[1])) {
            return Decision.DISCARD_REJECT;
        }
        try {
            long wall = Long.parseLong(parts[2]);
            long elapsed = Long.parseLong(parts[3]);
            int boot = Integer.parseInt(parts[4]);
            long wallAge = wallNow - wall;
            long elapsedAge = elapsedNow - elapsed;
            boolean fresh = wall > 0L && wallNow > 0L && elapsed >= 0L
                    && elapsedNow >= 0L && boot >= 0 && boot == currentBoot
                    && wallAge >= -2_000L && wallAge <= VALID_MS
                    && elapsedAge >= 0L && elapsedAge <= VALID_MS;
            if (!fresh) return Decision.DISCARD_REJECT;
            boolean same = MessageDigest.isEqual(
                    parts[1].getBytes(StandardCharsets.US_ASCII),
                    suppliedToken.getBytes(StandardCharsets.US_ASCII));
            return same ? Decision.CONSUME : Decision.KEEP_REJECT;
        } catch (NumberFormatException error) {
            return Decision.DISCARD_REJECT;
        }
    }
}

package com.yusd.pixel2dface;

/** Called under XposedEntry's state lock; independent of the transparent host's lifetime. */
final class UnlockResultGuard {
    static final long MAX_RESULT_AGE_MS = 18_000L;
    private String token;
    private long createdAt;
    private long generation;
    private boolean consumed;

    void start(String token, long now, long generation) {
        this.token = token;
        this.createdAt = now;
        this.generation = generation;
        consumed = false;
    }

    boolean consume(String candidate, long now, long currentGeneration) {
        if (consumed || !isCurrent(candidate, now, currentGeneration)) {
            return false;
        }
        consumed = true;
        return true;
    }

    boolean mayDeliver(String candidate, long now, long currentGeneration) {
        return consumed && isCurrent(candidate, now, currentGeneration);
    }

    void clear() {
        token = null;
        consumed = false;
    }

    private boolean isCurrent(String candidate, long now, long currentGeneration) {
        return token != null && token.equals(candidate) && generation == currentGeneration
                && now >= createdAt && now - createdAt <= MAX_RESULT_AGE_MS;
    }
}

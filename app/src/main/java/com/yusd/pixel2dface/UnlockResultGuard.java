package com.yusd.pixel2dface;

/** One-shot capability. Callers serialize access and bind it to the wake and Android user. */
final class UnlockResultGuard {
    static final long MAX_RESULT_AGE_MS = 18_000L;
    private String token;
    private long createdAt;
    private long generation;
    private int userId;
    private boolean consumed;
    private boolean delivered;

    void start(String token, long now, long generation, int userId) {
        clear();
        if (token == null || token.isEmpty() || now < 0L || userId < 0) {
            return;
        }
        this.token = token;
        this.createdAt = now;
        this.generation = generation;
        this.userId = userId;
        consumed = false;
    }

    boolean consume(String candidate, long now, long currentGeneration) {
        if (consumed || !isCurrent(candidate, now, currentGeneration)) {
            return false;
        }
        consumed = true;
        return true;
    }

    boolean mayDeliver(String candidate, long now, long currentGeneration, int currentUserId) {
        return consumed && !delivered && userId == currentUserId
                && isCurrent(candidate, now, currentGeneration);
    }

    boolean claimDelivery(String candidate, long now, long currentGeneration, int currentUserId) {
        if (!mayDeliver(candidate, now, currentGeneration, currentUserId)) {
            return false;
        }
        delivered = true;
        return true;
    }

    void clear() {
        token = null;
        consumed = false;
        delivered = false;
        userId = -1;
        createdAt = 0L;
        generation = 0L;
    }

    private boolean isCurrent(String candidate, long now, long currentGeneration) {
        return token != null && token.equals(candidate) && generation == currentGeneration
                && now >= createdAt && now - createdAt <= MAX_RESULT_AGE_MS;
    }
}

package com.yusd.pixel2dface;

/** Pure state machine; all methods are called under the coordinator's lock. */
final class UnlockSessionState {
    static final long WAKE_DEBOUNCE_MS = 500L;
    static final long LAUNCH_COOLDOWN_MS = 2_500L;
    static final long PASSIVE_AUTH_AGE_MS = 60_000L;
    static final long READINESS_WINDOW_MS = 1_500L;
    final UnlockResultGuard results = new UnlockResultGuard();
    long generation;
    long lastWakeAt = -1L;
    long lastLaunchAt = -1L;
    long preparingGeneration = -1L;
    long createdAt;
    long authenticatedAt = -1L;
    int authenticatedUser = -1;
    int sessionUser = -1;
    long configRevision = -1L;
    String token;
    boolean cameraHostActive;
    boolean hostReadyHandled;
    boolean readinessPending;
    private long bouncerAttempt;
    private boolean bouncerPending;
    private boolean bouncerDelivered;

    long wake(long now) {
        if (now < 0L) {
            return -1L;
        }
        if (lastWakeAt >= 0L && now >= lastWakeAt
                && now - lastWakeAt < WAKE_DEBOUNCE_MS) {
            return -1L;
        }
        // Native callbacks and screen broadcasts may arrive farther apart than the debounce
        // window. A real sleep resets these states; a late duplicate must not rotate a grant.
        if (isHostActive(now) || hasPassiveAuthentication(now) || hasPendingResult(now)
                || preparingGeneration == generation || readinessPending) {
            return -1L;
        }
        clearAuthentication();
        lastWakeAt = now;
        readinessPending = true;
        return ++generation;
    }

    boolean beginPreparation(long expectedGeneration, long now) {
        if (now < 0L || generation != expectedGeneration) return false;
        // This readiness attempt ends even if cooldown prevents a launch. Leaving it
        // pending would reject every later wake after the cooldown has already elapsed.
        readinessPending = false;
        if (preparingGeneration == generation
                || isHostActive(now) || hasPassiveAuthentication(now) || hasPendingResult(now)
                || (lastLaunchAt >= 0L && now >= lastLaunchAt
                && now - lastLaunchAt < LAUNCH_COOLDOWN_MS)) {
            return false;
        }
        preparingGeneration = generation;
        return true;
    }

    boolean isPreparing(long expectedGeneration) {
        return generation == expectedGeneration && preparingGeneration == expectedGeneration;
    }

    boolean isReadinessPending(long expectedGeneration) {
        return generation == expectedGeneration && readinessPending;
    }

    long readinessRetryDelay(long expectedGeneration, long now, long intervalMs) {
        if (!isReadinessPending(expectedGeneration) || now < lastWakeAt || intervalMs <= 0L) {
            return -1L;
        }
        long remaining = READINESS_WINDOW_MS - (now - lastWakeAt);
        return remaining > 0L ? Math.min(intervalMs, remaining) : -1L;
    }

    void finishReadiness(long expectedGeneration) {
        if (generation == expectedGeneration) {
            readinessPending = false;
        }
    }

    void finishPreparation(long expectedGeneration) {
        // A callback from an old wake cannot release the new wake's preparation slot.
        if (preparingGeneration == expectedGeneration) {
            preparingGeneration = -1L;
        }
    }

    boolean start(String capability, long now, long expectedGeneration, int userId) {
        if (!isPreparing(expectedGeneration) || capability == null
                || capability.isEmpty() || userId < 0 || now < 0L) {
            return false;
        }
        finishPreparation(expectedGeneration);
        token = capability;
        createdAt = now;
        sessionUser = userId;
        lastLaunchAt = now;
        cameraHostActive = true;
        hostReadyHandled = false;
        results.start(capability, now, generation, userId);
        return true;
    }

    boolean matches(String candidate) {
        return token != null && token.equals(candidate);
    }

    boolean isGeneration(long expectedGeneration) {
        return generation == expectedGeneration;
    }

    long confirmDeviceUnlocked(long expectedGeneration, boolean deviceLocked, boolean keyguardLocked) {
        // Showing=false is only a visual transition. Keep the weak grant until Android's
        // lock state confirms dismissal, otherwise clearing it can reverse an upward swipe.
        // isDeviceLocked=false includes an authenticated lock screen waiting for a swipe.
        // Both states must be false before the visual keyguard has really been dismissed.
        if (deviceLocked || keyguardLocked || !isGeneration(expectedGeneration)) {
            return -1L;
        }
        reset();
        return generation;
    }

    boolean configurationMatches(long revision) {
        return configRevision >= 0L && revision == configRevision;
    }

    boolean consumeHostReady(String candidate, long now) {
        if (hostReadyHandled || !matches(candidate) || !isHostActive(now)) {
            return false;
        }
        hostReadyHandled = true;
        return true;
    }

    boolean isHostActive(long now) {
        long start = authenticatedAt >= 0L ? authenticatedAt : createdAt;
        long maxAge = authenticatedAt >= 0L ? PASSIVE_AUTH_AGE_MS
                : UnlockResultGuard.MAX_RESULT_AGE_MS;
        return cameraHostActive && token != null && now >= start && now - start <= maxAge;
    }

    boolean hasPassiveAuthentication(long now) {
        return authenticatedAt >= 0L && authenticatedUser >= 0 && now >= authenticatedAt
                && now - authenticatedAt < PASSIVE_AUTH_AGE_MS;
    }

    void authenticate(int userId, long now) {
        if (userId != sessionUser || now < createdAt) {
            return;
        }
        authenticatedUser = userId;
        authenticatedAt = now;
    }

    long beginBouncerHandoff(long now) {
        if (!hasPassiveAuthentication(now) || bouncerPending || bouncerDelivered) return -1;
        bouncerPending = true;
        return ++bouncerAttempt;
    }

    void cancelBouncerHandoff() {
        bouncerPending = false;
    }

    boolean isBouncerHandoffCurrent(long expectedGeneration, long attempt, long now) {
        return generation == expectedGeneration && bouncerPending && bouncerAttempt == attempt
                && hasPassiveAuthentication(now);
    }

    boolean claimBouncerHandoff(long expectedGeneration, long attempt, int user,
            long revision, long now) {
        if (!isBouncerHandoffCurrent(expectedGeneration, attempt, now)
                || user != authenticatedUser || !configurationMatches(revision)) return false;
        bouncerPending = false;
        bouncerDelivered = true;
        return true;
    }

    void finishHost(String candidate) {
        if (matches(candidate)) {
            cameraHostActive = false;
            // The Activity can exit while the consumed success awaits configuration IPC.
            // Keep only that pending result; delivery and the watchdog still enforce its TTL.
            if (results.hasPendingDelivery()) return;
            token = null;
            sessionUser = -1;
            results.clear();
        }
    }

    private boolean hasPendingResult(long now) {
        return results.mayDeliver(token, now, generation, sessionUser);
    }

    boolean abort(String candidate, long expectedGeneration) {
        if (!isGeneration(expectedGeneration) || !matches(candidate)) return false;
        // Preserve wake/cooldown timestamps, but fence every in-flight result and callback.
        clearAuthentication();
        generation++;
        return true;
    }

    void reset() {
        clearAuthentication();
        lastLaunchAt = -1L;
        lastWakeAt = -1L;
        generation++;
    }

    private void clearAuthentication() {
        token = null;
        results.clear();
        sessionUser = -1;
        configRevision = -1L;
        preparingGeneration = -1L;
        cameraHostActive = false;
        hostReadyHandled = false;
        readinessPending = false;
        authenticatedAt = -1L;
        authenticatedUser = -1;
        bouncerAttempt = 0;
        bouncerPending = false;
        bouncerDelivered = false;
    }
}

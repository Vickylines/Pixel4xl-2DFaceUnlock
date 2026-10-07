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
        if (isHostActive(now) || hasPassiveAuthentication(now)
                || preparingGeneration == generation || readinessPending) {
            return -1L;
        }
        clearAuthentication();
        lastWakeAt = now;
        readinessPending = true;
        return ++generation;
    }

    boolean beginPreparation(long expectedGeneration, long now) {
        if (now < 0L || generation != expectedGeneration || preparingGeneration == generation
                || isHostActive(now) || hasPassiveAuthentication(now)
                || (lastLaunchAt >= 0L && now >= lastLaunchAt
                && now - lastLaunchAt < LAUNCH_COOLDOWN_MS)) {
            return false;
        }
        readinessPending = false;
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

    void finishHost(String candidate) {
        if (matches(candidate)) {
            token = null;
            sessionUser = -1;
            cameraHostActive = false;
            results.clear();
        }
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
    }
}

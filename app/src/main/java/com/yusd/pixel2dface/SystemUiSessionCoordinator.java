package com.yusd.pixel2dface;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XposedHelpers;

/** Owns wake, configuration IPC and one-shot authentication; visual hooks cannot grant access. */
final class SystemUiSessionCoordinator {
    private static final long PREPARATION_TIMEOUT_MS = 6_000L;
    private static final long READINESS_RETRY_MS = 120L;
    private static final String USER_SWITCHED = "android.intent.action.USER_SWITCHED";
    private final Object lock = new Object();
    private final UnlockSessionState session = new UnlockSessionState();
    private final Handler main;
    final SystemUiFeedback feedback;
    final SystemUiBouncer bouncer = new SystemUiBouncer();
    private final ExecutorService ipc = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Pixel2DFace-systemui-ipc");
        thread.setDaemon(true);
        return thread;
    });
    private WeakReference<Object> monitor = new WeakReference<>(null);
    private WeakReference<Context> context = new WeakReference<>(null);
    private BroadcastReceiver resultReceiver;
    private BroadcastReceiver powerReceiver;
    private boolean heartbeatPending;
    private long diagnosticGeneration = -1L;
    private int diagnosticLines;
    private static final int MAX_DIAGNOSTIC_LINES_PER_GENERATION = 32;
    boolean wakeMethodInstalled;
    boolean overlayInstalled;
    boolean occlusionInstalled;

    SystemUiSessionCoordinator(Handler main) {
        this.main = main;
        feedback = new SystemUiFeedback(main);
    }

    void attach(Object currentMonitor, Context currentContext) {
        Context application = currentContext.getApplicationContext();
        synchronized (lock) {
            monitor = new WeakReference<>(currentMonitor);
            context = new WeakReference<>(application != null ? application : currentContext);
        }
        onMain(() -> {
            Context attached = getContext();
            if (attached == null) {
                return;
            }
            ensureReceivers(attached);
            trace("attached", currentGeneration(), "results=" + (resultReceiver != null)
                    + ", power=" + (powerReceiver != null));
            heartbeat();
        });
    }

    void wake(Object currentMonitor, long delay) {
        long generation;
        synchronized (lock) {
            generation = session.wake(SystemClock.elapsedRealtime());
            if (generation < 0L) {
                trace("wake_rejected", session.generation,
                        "preparing=" + session.isPreparing(session.generation)
                        + ", hostActive=" + session.isHostActive(SystemClock.elapsedRealtime())
                        + ", passive=" + session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
                return;
            }
            monitor = new WeakReference<>(currentMonitor);
        }
        trace("wake_accepted", generation, "delayMs=" + delay);
        onMain(() -> {
            if (!isGeneration(generation)) {
                trace("wake_stale", generation, "current=" + currentGeneration());
                return;
            }
            feedback.remove();
            SystemUiCompat.clearStoredWeakAuthentication(currentMonitor, -1);
            feedback.unlockedIcon(false);
            main.postDelayed(() -> prepare(currentMonitor, generation), delay);
        });
    }

    void invalidate(Object currentMonitor, String reason) {
        long cancelledGeneration;
        String cancelledToken;
        synchronized (lock) {
            cancelledToken = session.token;
            session.reset();
            cancelledGeneration = session.generation;
        }
        cleanupCancelledSession(currentMonitor, cancelledGeneration);
        cancelCameraHost(cancelledToken);
        trace("cancelled", cancelledGeneration, reason);
    }

    void hidden() {
        // setShowingLocked/notifyKeyguardState also run during occlusion and dismissal
        // animations. Removing our view is safe; withdrawing the weak grant here is not.
        feedback.remove();
        long generation;
        boolean pendingAuthentication;
        synchronized (lock) {
            generation = session.generation;
            pendingAuthentication = session.token != null
                    || session.hasPassiveAuthentication(SystemClock.elapsedRealtime());
        }
        if (pendingAuthentication) {
            trace("surface_hidden", generation, "waiting for confirmed unlock");
            main.postDelayed(() -> confirmKeyguardExit(generation), 80L);
        }
    }

    private void confirmKeyguardExit(long generation) {
        Context attached = getContext();
        if (attached == null || !isGeneration(generation)) {
            return;
        }
        try {
            KeyguardManager keyguard = attached.getSystemService(KeyguardManager.class);
            if (keyguard == null) {
                return;
            }
            boolean deviceLocked = keyguard.isDeviceLocked();
            boolean keyguardLocked = keyguard.isKeyguardLocked();
            long cancelledGeneration;
            synchronized (lock) {
                cancelledGeneration = session.confirmDeviceUnlocked(generation, deviceLocked, keyguardLocked);
            }
            trace("keyguard_exit_check", generation, "deviceLocked=" + deviceLocked
                    + ", keyguardLocked=" + keyguardLocked
                    + ", confirmed=" + (cancelledGeneration >= 0L));
            if (cancelledGeneration >= 0L) {
                cleanupCancelledSession(getMonitor(), cancelledGeneration);
            }
        } catch (Throwable error) {
            // An unreadable lock state cannot prove dismissal. Sleep/policy/user callbacks
            // still revoke immediately, and a retained passive grant expires after 60s.
            SystemUiLog.error("Unable to confirm native keyguard dismissal", error);
        }
    }

    private void cleanupCancelledSession(Object currentMonitor, long cancelledGeneration) {
        onMain(() -> {
            synchronized (lock) {
                // Invalidation may originate off-main. Its old callback cannot erase a
                // newer wake's grant; the newer wake performs its own stale-cache cleanup.
                if (!session.isGeneration(cancelledGeneration)) {
                    return;
                }
                SystemUiCompat.clearStoredWeakAuthentication(currentMonitor, -1);
            }
            feedback.remove();
            feedback.unlockedIcon(false);
        });
    }

    boolean isCameraHostActive() {
        synchronized (lock) {
            return session.isHostActive(SystemClock.elapsedRealtime());
        }
    }

    boolean shouldKeepKeyguardVisible() {
        Context attached = getContext();
        if (attached == null || !isCameraHostActive()) return false;
        boolean owned = SystemUiCompat.isCameraHostForeground(attached);
        return owned && isCameraHostActive();
    }

    void cameraHostOcclusionSuppressed() {
        trace("host_occlusion_suppressed", currentGeneration(), "native keyguard kept visible");
    }

    private void prepare(Object currentMonitor, long generation) {
        Context attached = getContext();
        if (!isGeneration(generation)) {
            trace("prepare_stale", generation, "current=" + currentGeneration());
            return;
        }
        if (attached == null || currentMonitor == null || resultReceiver == null) {
            awaitReadiness(currentMonitor, generation, "context=" + (attached != null)
                    + ", monitor=" + (currentMonitor != null) + ", receiver=" + (resultReceiver != null));
            return;
        }
        if (!isLockedAndInteractive(attached)) {
            awaitReadiness(currentMonitor, generation, lockStateDetails(attached));
            return;
        }
        int user = SystemUiCompat.resolveSelectedUserId(currentMonitor);
        boolean allowed = SystemUiCompat.isWeakBiometricAllowed(currentMonitor, user);
        boolean canSkip = SystemUiCompat.canSkipBouncer(currentMonitor, user);
        if (!allowed || canSkip) {
            synchronized (lock) {
                session.finishReadiness(generation);
            }
            trace("prepare_rejected", generation, "weakAllowed=" + allowed + ", nativeCanSkip=" + canSkip);
            return;
        }
        synchronized (lock) {
            if (!session.beginPreparation(generation, SystemClock.elapsedRealtime())) {
                trace("prepare_rejected", generation, "session gate; preparing="
                        + session.isPreparing(generation) + ", passive="
                        + session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
                return;
            }
        }
        trace("prepare_started", generation, "configuration IPC queued");
        main.postDelayed(() -> cancelPreparation(generation, "configuration timeout"),
                PREPARATION_TIMEOUT_MS);
        ipc.execute(() -> {
            long startedAt = SystemClock.elapsedRealtime();
            Bundle state = null;
            String token = null;
            try {
                if (isPreparing(generation)) {
                    String candidate = UUID.randomUUID().toString() + UUID.randomUUID();
                    // A single provider transaction reads policy and publishes the grant.
                    state = attached.getContentResolver().call(
                            Uri.parse(Constants.PROVIDER_URI), "prepare_unlock", candidate, null);
                    if (isEnabled(state) && state.getBoolean("ok", false) && isPreparing(generation)) {
                        token = candidate;
                    }
                }
            } catch (Throwable error) {
                SystemUiLog.error("Module state/session IPC failed", error);
            }
            Bundle loadedState = state;
            String authorizedToken = token;
            trace("configuration_loaded", generation, "enabled=" + isEnabled(state)
                    + ", authorized=" + (token != null) + ", ipcMs="
                    + (SystemClock.elapsedRealtime() - startedAt));
            main.post(() -> configurationReady(currentMonitor, generation, user,
                    loadedState, authorizedToken));
        });
    }

    private void awaitReadiness(Object currentMonitor, long generation, String reason) {
        long delay;
        synchronized (lock) {
            delay = session.readinessRetryDelay(generation, SystemClock.elapsedRealtime(), READINESS_RETRY_MS);
            if (delay < 0L) {
                session.finishReadiness(generation);
            }
        }
        if (delay < 0L) {
            trace("readiness_expired", generation, reason);
            return;
        }
        trace("readiness_wait", generation, reason + ", retryMs=" + delay);
        // Retry within the same wake. Duplicate callbacks cannot rotate its generation;
        // sleep/user/policy invalidation fences every queued retry before any IPC or launch.
        main.postDelayed(() -> prepare(currentMonitor, generation), delay);
    }

    private void configurationReady(Object currentMonitor, long generation, int user,
            Bundle state, String token) {
        if (!isPreparing(generation)) {
            trace("configuration_stale", generation, "current=" + currentGeneration());
            return;
        }
        if (!isEnabled(state) || token == null || state.getLong("revision", -1L) < 0L) {
            cancelPreparation(generation, "disabled, unenrolled, lockout or authorization rejected");
            return;
        }
        Context attached = getContext();
        // Actual lock/power readiness replaces the fixed wake-to-launch delay. Preparation
        // already retries unreadable/not-yet-locked states within this wake's bounded window.
        // Re-evaluate user and weak-auth policy after every asynchronous boundary.
        if (attached == null || !isLockedAndInteractive(attached)
                || user != SystemUiCompat.resolveSelectedUserId(currentMonitor)
                || !SystemUiCompat.isWeakBiometricAllowed(currentMonitor, user)
                || SystemUiCompat.canSkipBouncer(currentMonitor, user)) {
            cancelPreparation(generation, "keyguard/user/policy changed during preparation");
            return;
        }
        synchronized (lock) {
            if (!session.start(token, SystemClock.elapsedRealtime(), generation, user)) {
                trace("launch_rejected", generation, "session no longer preparing");
                return;
            }
            session.configRevision = state.getLong("revision", -1L);
        }
        // First visible frame uses this wake's persisted choice, never the previous wake's style.
        feedback.scanning(state.getInt("animation_style", UnlockFaceAnimationView.STYLE_FACE_ID));
        try {
            Intent intent = new Intent().setComponent(new ComponentName(Constants.PACKAGE_NAME,
                    Constants.UNLOCK_HOST_ACTIVITY))
                    .putExtra(Constants.EXTRA_MODE, Constants.MODE_UNLOCK)
                    .putExtra(Constants.EXTRA_SESSION_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            ActivityOptions options = ActivityOptions.makeCustomAnimation(attached, 0, 0);
            if (Build.VERSION.SDK_INT >= 36) {
                options.setAllowPassThroughOnTouchOutside(true);
            }
            attached.startActivity(intent, options.toBundle());
            trace("host_launched", generation, "wakeAgeMs="
                    + (SystemClock.elapsedRealtime() - wakeStartedAt()));
            main.postDelayed(() -> expireSession(token, generation),
                    UnlockResultGuard.MAX_RESULT_AGE_MS + 1L);
        } catch (Throwable error) {
            synchronized (lock) {
                if (session.generation == generation) {
                    session.finishHost(token);
                }
            }
            feedback.remove();
            SystemUiLog.error("Unable to launch camera host", error);
        }
    }

    private void cancelPreparation(long generation, String reason) {
        synchronized (lock) {
            if (!session.isPreparing(generation)) {
                return;
            }
            session.finishPreparation(generation);
        }
        feedback.remove();
        trace("preparation_cancelled", generation, reason);
    }

    private void expireSession(String token, long generation) {
        synchronized (lock) {
            if (session.generation != generation || !session.matches(token)) {
                return;
            }
            if (session.hasPassiveAuthentication(SystemClock.elapsedRealtime())) {
                return;
            }
        }
        endFailedSession(token, generation, "result/passive authentication absent");
    }

    private void endFailedSession(String token, long generation, String reason) {
        long cancelledGeneration;
        synchronized (lock) {
            if (!session.abort(token, generation)) return;
            cancelledGeneration = session.generation;
        }
        onMain(() -> {
            if (!isGeneration(cancelledGeneration)) return;
            SystemUiCompat.clearStoredWeakAuthentication(getMonitor(), -1);
            feedback.unlockedIcon(false);
            feedback.failure();
        });
        cancelCameraHost(token);
        trace("failed_session_ended", cancelledGeneration, reason);
    }

    private void cancelCameraHost(String token) {
        if (token == null) return;
        onMain(() -> {
            Context attached = getContext();
            if (attached == null) return;
            try {
                attached.sendBroadcast(new Intent(Constants.ACTION_CANCEL_CAPTURE)
                        .setPackage(Constants.PACKAGE_NAME)
                        .putExtra(Constants.EXTRA_SESSION_TOKEN, token));
            } catch (RuntimeException unavailable) {
                SystemUiLog.error("Unable to cancel camera host", unavailable);
            }
        });
    }

    /** Reconciles success with a swipe that commits after the initial result was delivered. */
    void bouncerStateChanged() {
        onMain(() -> {
            synchronized (lock) {
                if (!session.hasPassiveAuthentication(SystemClock.elapsedRealtime())) return;
            }
            if (!bouncer.isShowing()) {
                synchronized (lock) { session.cancelBouncerHandoff(); }
                return;
            }
            Context attached = getContext();
            Object currentMonitor = getMonitor();
            Object currentBouncer = bouncer.current();
            if (attached == null || currentMonitor == null) return;
            long generation, revision, attempt;
            int user;
            synchronized (lock) {
                attempt = session.beginBouncerHandoff(SystemClock.elapsedRealtime());
                if (attempt < 0) return;
                generation = session.generation;
                revision = session.configRevision;
                user = session.authenticatedUser;
            }
            trace("bouncer_handoff_requested", generation, "committed swipe after passive success");
            ipc.execute(() -> {
                Bundle current = readState(attached);
                main.post(() -> {
                    synchronized (lock) {
                        if (!session.isBouncerHandoffCurrent(generation, attempt,
                                SystemClock.elapsedRealtime())) return;
                    }
                    if (!isEnabled(current) || current.getLong("revision", -1L) != revision
                            || user != SystemUiCompat.resolveSelectedUserId(currentMonitor)
                            || !SystemUiCompat.isWeakBiometricAllowed(currentMonitor, user)) {
                        invalidate(currentMonitor, "bouncer handoff configuration/user/policy revoked");
                        return;
                    }
                    PowerManager power = attached.getSystemService(PowerManager.class);
                    KeyguardManager keyguard = attached.getSystemService(KeyguardManager.class);
                    if (power == null || !power.isInteractive()) {
                        invalidate(currentMonitor, "bouncer handoff after screen-off");
                        return;
                    }
                    if (keyguard == null || !keyguard.isKeyguardLocked()
                            || bouncer.current() != currentBouncer || !bouncer.isShowing()) {
                        synchronized (lock) { session.cancelBouncerHandoff(); }
                        trace("bouncer_handoff_cancelled", generation, "bouncer no longer showing");
                        return;
                    }
                    // A native cache clear may have crossed the swipe. Restore only the
                    // still-valid passive grant after re-reading configuration and policy.
                    if (!SystemUiCompat.canSkipBouncer(currentMonitor, user)
                            && !SystemUiCompat.storeWeakAuthentication(currentMonitor, user)) {
                        invalidate(currentMonitor, "bouncer handoff weak cache unavailable");
                        return;
                    }
                    synchronized (lock) {
                        if (!session.claimBouncerHandoff(generation, attempt, user, revision,
                                SystemClock.elapsedRealtime())) {
                            SystemUiCompat.clearStoredWeakAuthentication(currentMonitor, user);
                            return;
                        }
                    }
                    if (bouncer.completeWeakAuthentication(currentBouncer)) {
                        feedback.remove();
                        trace("bouncer_handoff_dispatched", generation, "native weak completion requested");
                    } else {
                        trace("bouncer_handoff_unavailable", generation, "retaining native swipe state");
                    }
                });
            });
        });
    }

    void restorePassiveAuthentication(Object currentMonitor) {
        onMain(() -> {
            int expectedUser;
            long generation;
            long revision;
            synchronized (lock) {
                if (!session.hasPassiveAuthentication(SystemClock.elapsedRealtime())) {
                    return;
                }
                expectedUser = session.authenticatedUser;
                generation = session.generation;
                revision = session.configRevision;
            }
            trace("passive_restore_requested", generation, "native biometric cache cleared");
            Context attached = getContext();
            if (attached == null) {
                return;
            }
            ipc.execute(() -> {
                Bundle current = readState(attached);
                main.post(() -> {
                    synchronized (lock) {
                        if (session.generation != generation
                                || !session.hasPassiveAuthentication(SystemClock.elapsedRealtime())) {
                            trace("passive_restore_stale", generation, "current=" + session.generation);
                            return;
                        }
                    }
                    if (!isEnabled(current) || current.getLong("revision", -1L) != revision
                            || expectedUser != SystemUiCompat.resolveSelectedUserId(currentMonitor)
                            || !SystemUiCompat.isWeakBiometricAllowed(currentMonitor, expectedUser)) {
                        invalidate(currentMonitor, "passive configuration/user/weak policy revoked");
                        return;
                    }
                    PowerManager power = attached.getSystemService(PowerManager.class);
                    KeyguardManager keyguard = attached.getSystemService(KeyguardManager.class);
                    if (power == null || !power.isInteractive()) {
                        invalidate(currentMonitor, "passive restore after screen-off");
                        return;
                    }
                    if (keyguard == null || !keyguard.isKeyguardLocked()) {
                        trace("passive_restore_deferred", generation, lockStateDetails(attached));
                        confirmKeyguardExit(generation);
                        return;
                    }
                    // Authenticated + keyguard showing is a valid wait-for-swipe state.
                    // isDeviceLocked=false must not revoke that already accepted grant.
                    if (SystemUiCompat.canSkipBouncer(currentMonitor, expectedUser)) {
                        trace("passive_restore_existing_native", generation,
                                "native authentication already permits swipe");
                        bouncerStateChanged();
                        return;
                    }
                    if (!SystemUiCompat.storeWeakAuthentication(currentMonitor, expectedUser)) {
                        trace("passive_restore_rejected", generation, "adapter="
                                + SystemUiCompat.getAuthenticationAdapterLabel());
                        invalidate(currentMonitor, "passive cache adapter rejected restoration");
                        return;
                    }
                    synchronized (lock) {
                        // Native callbacks can revoke this grant while the adapter writes it.
                        // Remove that insertion before returning to SystemUI's event loop.
                        if (session.generation != generation
                                || !session.hasPassiveAuthentication(SystemClock.elapsedRealtime())) {
                            SystemUiCompat.clearStoredWeakAuthentication(currentMonitor, expectedUser);
                            trace("passive_restore_revoked", generation, "revoked or expired during adapter write");
                            return;
                        }
                    }
                    trace("passive_restore_completed", generation,
                            "adapter=" + SystemUiCompat.getAuthenticationAdapterLabel());
                    bouncerStateChanged();
                });
            });
        });
    }

    private void handleResult(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Constants.ACTION_CONFIGURATION_CHANGED.equals(action)) {
            // The receiver requires the module's signature permission for every signal,
            // including revocations, so other apps cannot repeatedly cancel recognition.
            invalidate(getMonitor(), "module configuration changed");
            heartbeat();
            return;
        }
        if (!Constants.ACTION_UNLOCK_RESULT.equals(action)
                && !Constants.ACTION_LOW_LIGHT.equals(action)
                && !Constants.ACTION_CAMERA_HOST_READY.equals(action)
                && !Constants.ACTION_CAMERA_HOST_FINISHED.equals(action)) {
            return;
        }
        String token;
        try {
            token = intent.getStringExtra(Constants.EXTRA_SESSION_TOKEN);
        } catch (RuntimeException malformed) {
            return;
        }
        if (Constants.ACTION_LOW_LIGHT.equals(action)) {
            synchronized (lock) {
                if (!session.matches(token) || !session.isHostActive(SystemClock.elapsedRealtime())
                        || !session.results.isAwaitingResult(token, SystemClock.elapsedRealtime(),
                                session.generation)) return;
            }
            if (isLockedAndInteractive(getContext())) feedback.lowLight();
            return;
        }
        if (Constants.ACTION_CAMERA_HOST_READY.equals(action)) {
            synchronized (lock) {
                if (!session.consumeHostReady(token, SystemClock.elapsedRealtime())) {
                    return;
                }
            }
            try {
                Bundle extras = intent.getExtras();
                IBinder activity = extras == null ? null : extras.getBinder(Constants.EXTRA_ACTIVITY_TOKEN);
                if (activity != null) {
                    ipc.execute(() -> {
                        synchronized (lock) {
                            if (!session.matches(token)
                                    || !session.isHostActive(SystemClock.elapsedRealtime())) {
                                return;
                            }
                        }
                        disableInputSink(activity);
                    });
                }
            } catch (RuntimeException malformed) {
                SystemUiLog.info("Rejected malformed camera-host ready signal");
            }
            return;
        }
        if (Constants.ACTION_CAMERA_HOST_FINISHED.equals(action)) {
            boolean matched;
            synchronized (lock) {
                matched = session.matches(token);
                session.finishHost(token);
            }
            if (matched) {
                trace("host_finished", currentGeneration(), "matching session; passive="
                        + hasPassiveAuthentication());
                feedback.remove();
            }
            return;
        }
        Object currentMonitor = getMonitor();
        long generation;
        long revision;
        boolean success = intent.getBooleanExtra(Constants.EXTRA_SUCCESS, false);
        long resultRevision = intent.getLongExtra(Constants.EXTRA_STORE_REVISION, -1L);
        synchronized (lock) {
            if (!session.matches(token)) {
                trace("result_rejected", session.generation, "session mismatch");
                return;
            }
            if (success && !session.configurationMatches(resultRevision)) {
                trace("result_rejected", session.generation, "configuration changed");
                return;
            }
            if (!session.results.consume(token, SystemClock.elapsedRealtime(), session.generation)) {
                trace("result_rejected", session.generation, "expired/stale/already consumed");
                return;
            }
            session.lastLaunchAt = SystemClock.elapsedRealtime();
            generation = session.generation;
            revision = session.configRevision;
        }
        trace("result_received", generation, "success=" + success
                + ", sessionAgeMs=" + sessionAgeMs());
        if (!success || currentMonitor == null) {
            endFailedSession(token, generation, success ? "monitor unavailable" : "capture failed");
            return;
        }
        Context attached = getContext();
        if (attached == null) {
            return;
        }
        // The module can be disabled or re-enrolled while CameraX is recognizing. Re-read
        // its current revision off the main thread before granting any native weak state.
        ipc.execute(() -> {
            Bundle current = readState(attached);
            main.post(() -> {
                if (!isGeneration(generation)) {
                    trace("result_recheck_stale", generation, "current=" + currentGeneration());
                    return;
                }
                if (!isEnabled(current) || current.getLong("revision", -1L) != revision) {
                    trace("result_recheck_rejected", generation, "configuration disabled/changed/locked out");
                    endFailedSession(token, generation, "configuration disabled/changed/locked out");
                    return;
                }
                trace("result_recheck_passed", generation, "current configuration confirmed");
                deliverAuthentication(currentMonitor, token, generation);
            });
        });
    }

    private void deliverAuthentication(Object currentMonitor, String token, long generation) {
        Context attached = getContext();
        int user = SystemUiCompat.resolveSelectedUserId(currentMonitor);
        if (attached == null || !isLockedAndInteractive(attached)
                || !SystemUiCompat.isWeakBiometricAllowed(currentMonitor, user)) {
            trace("delivery_rejected", generation, lockStateDetails(attached)
                    + ", weakAllowed=" + SystemUiCompat.isWeakBiometricAllowed(currentMonitor, user));
            endFailedSession(token, generation, "lock state or weak policy rejected delivery");
            return;
        }
        synchronized (lock) {
            if (session.generation != generation || !session.results.claimDelivery(token,
                    SystemClock.elapsedRealtime(), generation, user)) {
                trace("delivery_rejected", generation, "generation/user/age/already delivered");
                return;
            }
        }
        try {
            if (bouncer.isShowing()) {
                trace("delivery_bouncer", generation, "using weak native face callback");
                feedback.remove();
                if (!SystemUiCompat.dispatchFaceAuthenticated(currentMonitor, user)) {
                    trace("delivery_rejected", generation, "native face callback rejected");
                    endFailedSession(token, generation, "native face callback rejected");
                    return;
                }
                trace("delivery_completed", generation, "bouncer adapter="
                        + SystemUiCompat.getAuthenticationAdapterLabel());
                heartbeat();
                return;
            }
            if (!SystemUiCompat.storeWeakAuthentication(currentMonitor, user)) {
                trace("delivery_rejected", generation, "passive adapter="
                        + SystemUiCompat.getAuthenticationAdapterLabel()
                        + ", nativeCanSkip=" + SystemUiCompat.canSkipBouncer(currentMonitor, user));
                endFailedSession(token, generation, "passive adapter rejected");
                return;
            }
            synchronized (lock) {
                long completedAt = SystemClock.elapsedRealtime();
                // Reflection/native callbacks can both revoke and outlive the result ticket.
                // A claimed delivery must not turn an expired insertion into a fresh 60s grant.
                if (session.generation != generation || !session.results.isDeliveredAndCurrent(
                        token, completedAt, generation, user)) {
                    SystemUiCompat.clearStoredWeakAuthentication(currentMonitor, user);
                    trace("delivery_revoked", generation, "revoked or expired during adapter write");
                    return;
                }
                session.authenticate(user, completedAt);
            }
            trace("delivery_completed", generation, "passive adapter="
                    + SystemUiCompat.getAuthenticationAdapterLabel() + ", nativeCanSkip="
                    + SystemUiCompat.canSkipBouncer(currentMonitor, user));
            feedback.unlockedIcon(true);
            feedback.success();
            bouncerStateChanged();
            refreshTimeout(attached);
            heartbeat();
            main.postDelayed(() -> {
                synchronized (lock) {
                    if (session.generation != generation || session.authenticatedUser != user) {
                        return;
                    }
                }
                invalidate(currentMonitor, "passive authentication expired");
            }, UnlockSessionState.PASSIVE_AUTH_AGE_MS);
        } catch (Throwable error) {
            invalidate(currentMonitor, "authentication adapter failure");
            SystemUiLog.error("Unable to apply weak face authentication", error);
        }
    }

    private void ensureReceivers(Context attached) {
        if (resultReceiver == null) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    try {
                        handleResult(intent);
                    } catch (Throwable error) {
                        SystemUiLog.error("Rejected camera-host signal", error);
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Constants.ACTION_UNLOCK_RESULT);
            filter.addAction(Constants.ACTION_CAMERA_HOST_READY);
            filter.addAction(Constants.ACTION_CAMERA_HOST_FINISHED);
            filter.addAction(Constants.ACTION_LOW_LIGHT);
            filter.addAction(Constants.ACTION_CONFIGURATION_CHANGED);
            if (register(attached, receiver, filter, Constants.PERMISSION_SEND_SIGNALS)) {
                resultReceiver = receiver;
            }
        }
        if (powerReceiver == null) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    String action = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        Object current = getMonitor();
                        if (current != null) {
                            wake(current, 100L);
                        }
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action) || USER_SWITCHED.equals(action)) {
                        invalidate(getMonitor(), action);
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        // This protected system broadcast follows real credential/keyguard
                        // dismissal, unlike the transitional showing=false hook callbacks.
                        trace("user_present", currentGeneration(), "checking native lock state");
                        confirmKeyguardExit(currentGeneration());
                    } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                        heartbeat();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_UNLOCKED);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(USER_SWITCHED);
            if (register(attached, receiver, filter, null)) {
                powerReceiver = receiver;
            }
        }
    }

    @SuppressWarnings("deprecation")
    private boolean register(Context attached, BroadcastReceiver receiver, IntentFilter filter,
            String senderPermission) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                attached.registerReceiver(receiver, filter, senderPermission, main, Context.RECEIVER_EXPORTED);
            } else {
                attached.registerReceiver(receiver, filter, senderPermission, main);
            }
            return true;
        } catch (Throwable error) {
            SystemUiLog.error("Unable to register SystemUI receiver", error);
            return false;
        }
    }

    void heartbeat() {
        Context attached = getContext();
        if (attached == null) {
            return;
        }
        synchronized (lock) {
            if (heartbeatPending) {
                return;
            }
            heartbeatPending = true;
        }
        ipc.execute(() -> {
            try {
                UserManager users = attached.getSystemService(UserManager.class);
                if (users != null && !users.isUserUnlocked()) {
                    return;
                }
                Bundle diagnostics = new Bundle();
                diagnostics.putInt("module_version", BuildConfig.VERSION_CODE);
                diagnostics.putString("platform", SystemUiCompat.platformLabel());
                diagnostics.putBoolean("wake_method", wakeMethodInstalled);
                diagnostics.putBoolean("power_broadcast", powerReceiver != null);
                diagnostics.putBoolean("native_overlay", overlayInstalled);
                diagnostics.putBoolean("occlusion_guard", occlusionInstalled);
                diagnostics.putString("authentication_adapter", SystemUiCompat.getAuthenticationAdapterLabel());
                attached.getContentResolver().call(Uri.parse(Constants.PROVIDER_URI), "heartbeat", null, diagnostics);
            } catch (Throwable error) {
                SystemUiLog.error("Unable to publish hook heartbeat", error);
            } finally {
                synchronized (lock) {
                    heartbeatPending = false;
                }
            }
        });
    }

    private boolean isGeneration(long generation) {
        synchronized (lock) {
            return session.isGeneration(generation);
        }
    }

    private long currentGeneration() {
        synchronized (lock) {
            return session.generation;
        }
    }

    private long wakeStartedAt() {
        synchronized (lock) {
            return session.lastWakeAt;
        }
    }

    private long sessionAgeMs() {
        synchronized (lock) {
            return SystemClock.elapsedRealtime() - session.createdAt;
        }
    }

    private boolean hasPassiveAuthentication() {
        synchronized (lock) {
            return session.hasPassiveAuthentication(SystemClock.elapsedRealtime());
        }
    }

    private void trace(String stage, long generation, String detail) {
        synchronized (lock) {
            if (diagnosticGeneration != session.generation) {
                diagnosticGeneration = session.generation;
                diagnosticLines = 0;
            }
            if (diagnosticLines++ >= MAX_DIAGNOSTIC_LINES_PER_GENERATION) {
                return;
            }
        }
        SystemUiLog.info("Stage " + stage + " generation=" + generation + " " + detail);
    }

    private static String lockStateDetails(Context attached) {
        if (attached == null) {
            return "context unavailable";
        }
        try {
            KeyguardManager keyguard = attached.getSystemService(KeyguardManager.class);
            PowerManager power = attached.getSystemService(PowerManager.class);
            return "deviceLocked=" + (keyguard == null ? "unknown" : keyguard.isDeviceLocked())
                    + ", keyguardLocked=" + (keyguard == null ? "unknown" : keyguard.isKeyguardLocked())
                    + ", interactive=" + (power == null ? "unknown" : power.isInteractive());
        } catch (Throwable error) {
            return "lock state unavailable";
        }
    }

    private boolean isPreparing(long generation) {
        synchronized (lock) {
            return session.isPreparing(generation);
        }
    }

    private Context getContext() {
        synchronized (lock) {
            return context.get();
        }
    }

    private Object getMonitor() {
        synchronized (lock) {
            return monitor.get();
        }
    }

    private void onMain(Runnable task) {
        if (Looper.myLooper() == main.getLooper()) {
            task.run();
        } else {
            main.post(task);
        }
    }

    private static boolean isEnabled(Bundle state) {
        return state != null && state.getBoolean("enabled", false)
                && state.getBoolean("enrolled", false)
                && state.getLong("lockout_until", 0L) <= System.currentTimeMillis();
    }

    private static Bundle readState(Context attached) {
        try {
            return attached.getContentResolver().call(
                    Uri.parse(Constants.PROVIDER_URI), "state", null, null);
        } catch (Throwable error) {
            SystemUiLog.error("Unable to revalidate module configuration", error);
            return null;
        }
    }

    private static boolean isLockedAndInteractive(Context attached) {
        try {
            KeyguardManager keyguard = attached.getSystemService(KeyguardManager.class);
            PowerManager power = attached.getSystemService(PowerManager.class);
            return keyguard != null && keyguard.isDeviceLocked() && keyguard.isKeyguardLocked()
                    && power != null && power.isInteractive();
        } catch (Throwable error) {
            return false;
        }
    }

    private static void disableInputSink(IBinder activity) {
        try {
            Class<?> clientClass = XposedHelpers.findClass("android.app.ActivityClient", null);
            Object client = XposedHelpers.callStaticMethod(clientClass, "getInstance");
            XposedHelpers.callMethod(client, "setActivityRecordInputSinkEnabled", activity, false);
        } catch (Throwable error) {
            SystemUiLog.error("Camera-host input-sink adapter unavailable", error);
        }
    }

    private static void refreshTimeout(Context attached) {
        try {
            PowerManager power = attached.getSystemService(PowerManager.class);
            if (power != null) {
                XposedHelpers.callMethod(power, "userActivity", SystemClock.uptimeMillis(), 0, 0);
            }
        } catch (Throwable error) {
            SystemUiLog.error("Unable to refresh keyguard timeout", error);
        }
    }
}

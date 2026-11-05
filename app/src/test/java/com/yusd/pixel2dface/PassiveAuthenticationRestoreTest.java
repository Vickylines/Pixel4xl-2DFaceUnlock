package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, instrumentedPackages = "com.yusd.pixel2dface", shadows = {
        AtomicFileHostShadow.class, PassiveAuthenticationRestoreTest.NativeCache.class,
        PassiveAuthenticationRestoreTest.HookLog.class})
public final class PassiveAuthenticationRestoreTest {
    private final Object monitor = new Object();
    private SystemUiSessionCoordinator coordinator;
    private ExecutorService ipc;

    @Before public void authenticatedLockscreen() {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(TemplateStore.saveIdentityModel(context,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        coordinator = new SystemUiSessionCoordinator(new Handler(Looper.getMainLooper()));
        ReflectionHelpers.setField(coordinator, "context", new WeakReference<>(context));
        ReflectionHelpers.setField(coordinator, "monitor", new WeakReference<>(monitor));
        ipc = ReflectionHelpers.getField(coordinator, "ipc");
        UnlockSessionState session = ReflectionHelpers.getField(coordinator, "session");
        long now = SystemClock.elapsedRealtime();
        long generation = session.wake(now);
        assertTrue(session.beginPreparation(generation, now));
        assertTrue(session.start("synthetic-passive-restore-token-00001", now, generation, 0));
        session.configRevision = TemplateStore.readState(context).revision;
        session.authenticate(0, now);
        assertTrue(session.hasPassiveAuthentication(now));
        NativeCache.stored = false;
        NativeCache.writes = 0;
        NativeCache.duringWrite = () -> { };
    }

    @After public void closeIpc() {
        if (ipc != null) ipc.shutdownNow();
    }

    @Test public void liveSessionCanRestoreTheClearedNativeCache() throws Exception {
        restore();
        assertEquals(1, NativeCache.writes);
        assertTrue(NativeCache.stored);
    }

    @Test public void revocationDuringAdapterWriteCannotRestoreOldAuthentication() throws Exception {
        NativeCache.duringWrite = () -> coordinator.invalidate(monitor, "native policy revocation");
        restore();
        assertEquals(1, NativeCache.writes);
        assertFalse(NativeCache.stored);
    }

    @Test public void expiryDuringAdapterWriteCannotExtendAuthentication() throws Exception {
        NativeCache.duringWrite = () -> SystemClock.sleep(UnlockSessionState.PASSIVE_AUTH_AGE_MS);
        restore();
        assertEquals(1, NativeCache.writes);
        assertFalse(NativeCache.stored);
    }

    private void restore() throws Exception {
        coordinator.restorePassiveAuthentication(monitor);
        ipc.submit(() -> { }).get(5, TimeUnit.SECONDS);
        shadowOf(Looper.getMainLooper()).idle();
    }

    // Only the device-specific reflection boundary is replaced. The coordinator,
    // provider, settings files, IPC executor and main-loop callback remain real.
    @Implements(value = SystemUiCompat.class, isInAndroidSdk = false)
    public static final class NativeCache {
        static Runnable duringWrite;
        static boolean stored;
        static int writes;
        @Implementation protected static int resolveSelectedUserId(Object monitor) { return 0; }
        @Implementation protected static boolean isWeakBiometricAllowed(Object monitor, int user) { return true; }
        @Implementation protected static boolean canSkipBouncer(Object monitor, int user) { return false; }
        @Implementation protected static boolean storeWeakAuthentication(Object monitor, int user) {
            writes++;
            duringWrite.run();
            stored = true;
            return true;
        }
        @Implementation protected static void clearStoredWeakAuthentication(Object monitor, int user) {
            stored = false;
        }
    }

    @Implements(value = SystemUiLog.class, isInAndroidSdk = false)
    public static final class HookLog {
        @Implementation protected static void info(String message) { }
        @Implementation protected static void error(String message, Throwable error) {
            throw new AssertionError(message, error);
        }
    }
}

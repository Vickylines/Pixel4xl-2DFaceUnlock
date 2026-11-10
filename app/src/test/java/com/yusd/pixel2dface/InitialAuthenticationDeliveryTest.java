package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
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

/** Keeps the real coordinator and ticket clock; replaces only device-specific adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, instrumentedPackages = "com.yusd.pixel2dface", shadows = {
        InitialAuthenticationDeliveryTest.NativeCache.class,
        InitialAuthenticationDeliveryTest.HookLog.class})
public final class InitialAuthenticationDeliveryTest {
    private static final String TOKEN = "synthetic-initial-delivery-token-00001";
    private final Object monitor = new Object();
    private SystemUiSessionCoordinator coordinator;
    private UnlockSessionState session;
    private Handler main;
    private long generation;

    @Before public void consumedResultOnLockedScreen() {
        Context context = RuntimeEnvironment.getApplication();
        shadowOf(context.getSystemService(PowerManager.class)).setIsInteractive(true);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        main = new Handler(Looper.getMainLooper());
        coordinator = new SystemUiSessionCoordinator(main);
        ReflectionHelpers.setField(coordinator, "context", new WeakReference<>(context));
        ReflectionHelpers.setField(coordinator, "monitor", new WeakReference<>(monitor));
        session = ReflectionHelpers.getField(coordinator, "session");
        long now = SystemClock.elapsedRealtime();
        generation = session.wake(now);
        assertTrue(session.beginPreparation(generation, now));
        assertTrue(session.start(TOKEN, now, generation, 0));
        session.configRevision = 1;
        assertTrue(session.results.consume(TOKEN, now, generation));
        NativeCache.stored = false;
        NativeCache.writes = 0;
        NativeCache.duringWrite = () -> { };
    }

    @After public void closeIpc() {
        main.removeCallbacksAndMessages(null);
        ExecutorService ipc = ReflectionHelpers.getField(coordinator, "ipc");
        ipc.shutdownNow();
    }

    @Test public void validInitialDeliveryEstablishesOnePassiveGrant() {
        deliver();
        assertEquals(1, NativeCache.writes);
        assertTrue(NativeCache.stored);
        assertTrue(session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
        assertFalse(session.results.claimDelivery(TOKEN, SystemClock.elapsedRealtime(), generation, 0));
    }

    @Test public void expiryDuringNativeWriteCannotRenewTheResultForSixtySeconds() {
        SystemClock.sleep(UnlockResultGuard.MAX_RESULT_AGE_MS - 1);
        NativeCache.duringWrite = () -> SystemClock.sleep(2);
        deliver();
        assertEquals(1, NativeCache.writes);
        assertFalse("Expired native insertion must be withdrawn", NativeCache.stored);
        assertFalse("An expired result cannot start a fresh passive lifetime",
                session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
    }

    @Test public void revocationDuringNativeWriteCannotEstablishAuthentication() {
        NativeCache.duringWrite = () -> coordinator.invalidate(monitor, "policy changed");
        deliver();
        assertEquals(1, NativeCache.writes);
        assertFalse(NativeCache.stored);
        assertFalse(session.hasPassiveAuthentication(SystemClock.elapsedRealtime()));
    }

    private void deliver() {
        ReflectionHelpers.callInstanceMethod(coordinator, "deliverAuthentication",
                from(Object.class, monitor), from(String.class, TOKEN), from(long.class, generation));
    }

    @Implements(value = SystemUiCompat.class, isInAndroidSdk = false)
    public static final class NativeCache {
        static Runnable duringWrite;
        static boolean stored;
        static int writes;
        @Implementation protected static int resolveSelectedUserId(Object monitor) { return 0; }
        @Implementation protected static boolean isWeakBiometricAllowed(Object monitor, int user) { return true; }
        @Implementation protected static boolean canSkipBouncer(Object monitor, int user) { return false; }
        @Implementation protected static String getAuthenticationAdapterLabel() { return "test-adapter"; }
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
        // The optional native userActivity adapter is unavailable in the host JVM.
        @Implementation protected static void error(String message, Throwable error) { }
    }
}

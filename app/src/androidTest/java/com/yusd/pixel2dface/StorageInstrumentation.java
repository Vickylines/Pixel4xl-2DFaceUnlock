package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only storage regression checks with synthetic models in an isolated directory. */
public final class StorageInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = isolatedContext();
            roundTripAndConcurrentUpdates(context);
            atomicRecovery(context);
            grants(context);
            malformedModels(context);
            failedWrites();
            result.putString("result", "PASS");
            result.putInt("checks", checks);
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("result", "FAIL: " + failure);
            result.putInt("checks", checks);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Context isolatedContext() {
        final File root = new File(getTargetContext().getCacheDir(),
                "storage-regression-" + SystemClock.elapsedRealtimeNanos());
        check(root.mkdirs(), "create isolated test directory");
        return new ContextWrapper(getTargetContext()) {
            @Override public File getFilesDir() { return root; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.dataDir = root.getAbsolutePath();
                return info;
            }
        };
    }

    private void roundTripAndConcurrentUpdates(Context context) throws Exception {
        IdentityModel model = syntheticModel();
        check(TemplateStore.saveIdentityModel(context, model), "save model");
        TemplateStore.RecognitionSettings settings = TemplateStore.loadRecognitionSettings(context);
        check(settings.model != null && settings.state.enabled && settings.state.enrolled,
                "single-read model and settings");
        check(settings.model.compare(model.textureCentroid, model.geometryCentroid,
                settings.threshold).accepted, "model round trip preserves matching");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int lane = 0; lane < 3; lane++) {
            final int current = lane;
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        if (current == 0) TemplateStore.setEnabled(context, i % 2 == 0);
                        if (current == 1) TemplateStore.setRecognitionProfile(context, i % 3);
                        if (current == 2) TemplateStore.setAnimationStyle(context, i % 2);
                    }
                } catch (Throwable error) { failure.set(error); }
            });
            threads.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join(10_000L);
        for (Thread thread : threads) check(!thread.isAlive(), "writer completed");
        check(failure.get() == null, "concurrent writers completed without error");
        check(!TemplateStore.isEnabled(context), "enabled update retained");
        check(TemplateStore.getRecognitionProfile(context) == 1, "profile update retained");
        check(TemplateStore.getAnimationStyle(context) == 1, "animation update retained");
        check(TemplateStore.loadIdentityModel(context) != null, "template retained after writes");
        for (int i = 0; i < 5; i++) TemplateStore.recordFailure(context);
        check(TemplateStore.getLockoutUntil(context) > System.currentTimeMillis(), "failure lockout");
        TemplateStore.recordSuccess(context);
        check(TemplateStore.getLockoutUntil(context) == 0L, "success clears lockout");
    }

    private void atomicRecovery(Context context) throws Exception {
        File base = new File(context.getFilesDir(), "face2d.properties");
        File backup = new File(context.getFilesDir(), "face2d.properties.bak");
        check(base.renameTo(backup), "simulate interrupted atomic write");
        check(TemplateStore.loadIdentityModel(context) != null, "recover backup-only model");
        check(base.isFile(), "AtomicFile recovered base");
    }

    private void grants(Context context) throws Exception {
        String token = "synthetic-session-token-00000000000001";
        String wrong = "synthetic-session-token-00000000000002";
        check(TemplateStore.authorizeUnlockSession(context, token), "authorize session");
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, wrong), "wrong grant rejected");
        check(TemplateStore.consumeAuthorizedUnlockSession(context, token), "wrong token kept real grant");
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, token), "grant is single use");
        File grant = new File(context.getFilesDir(), "unlock.session");
        try (FileOutputStream out = new FileOutputStream(grant)) {
            out.write((token + "\n" + (System.currentTimeMillis() - 20_000L) + "\n"
                    + (SystemClock.elapsedRealtime() - 20_000L)).getBytes(StandardCharsets.UTF_8));
        }
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, token), "expired grant rejected");
        check(!grant.exists(), "expired grant discarded");
    }

    private void malformedModels(Context context) throws Exception {
        File base = new File(context.getFilesDir(), "face2d.properties");
        Properties props = new Properties();
        try (FileInputStream in = new AtomicFile(base).openRead()) { props.load(in); }
        props.setProperty("geometry_threshold", "Infinity");
        try (FileOutputStream out = new FileOutputStream(base)) { props.store(out, "synthetic"); }
        check(TemplateStore.loadIdentityModel(context) == null, "invalid model threshold rejected");
        check(TemplateStore.saveIdentityModel(context, syntheticModel()), "restore synthetic model");
        TemplateStore.clearTemplates(context);
        check(TemplateStore.loadIdentityModel(context) == null && !TemplateStore.isEnabled(context),
                "clear disables and removes model");
        check(TemplateStore.getRecognitionProfile(context) == 1
                && TemplateStore.getAnimationStyle(context) == 1, "clear preserves preferences");
    }

    private void failedWrites() throws Exception {
        final File invalidParent = new File(isolatedContext().getFilesDir(), "not-a-directory");
        check(invalidParent.createNewFile(), "create write failure fixture");
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public File getFilesDir() { return invalidParent; }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.dataDir = invalidParent.getAbsolutePath();
                return info;
            }
        };
        check(!TemplateStore.saveIdentityModel(context, syntheticModel()), "save failure reported");
    }

    private static IdentityModel syntheticModel() {
        List<float[]> textures = new ArrayList<>();
        List<float[]> geometries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            float[] texture = new float[LbpDescriptor.LENGTH];
            for (int cell = 0; cell < IdentityModel.CELL_COUNT; cell++) texture[cell * 256 + 4] = 1f;
            textures.add(texture);
            geometries.add(new float[]{0.72f, 0.40f, 0.22f, 0.42f, 0.30f, 0.21f, 0f});
        }
        return IdentityModel.enroll(textures, geometries);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

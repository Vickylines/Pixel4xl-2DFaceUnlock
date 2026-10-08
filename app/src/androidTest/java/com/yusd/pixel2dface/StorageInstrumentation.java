package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AtomicFile;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Device-only storage regression checks with synthetic models in an isolated directory. */
public final class StorageInstrumentation extends Instrumentation {
    private final AtomicInteger checks = new AtomicInteger();
    private final List<File> testRoots = new ArrayList<>();
    private final AtomicInteger configurationSignals = new AtomicInteger();

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
            combinedUnlockStartup();
            settingsRevocation(context);
            malformedModels(context);
            legacyMigration();
            historicalBackupDeletion();
            corruptFilePreservation();
            providerProtocol();
            failedWrites();
            neuralStorage();
            verifiedEnrollmentTransfer();
            verifiedLegacyRollback();
            checks.addAndGet(NeuralInstrumentationChecks.run(getTargetContext(), result));
            result.putString("result", "PASS");
            result.putInt("checks", checks.get());
            cleanup();
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            android.util.Log.e("Pixel2DFaceTest", "Instrumentation failed", failure);
            result.putString("result", "FAIL: " + failure);
            result.putInt("checks", checks.get());
            cleanup();
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Context isolatedContext() {
        final File root = new File(getTargetContext().getCacheDir(),
                "storage-regression-" + SystemClock.elapsedRealtimeNanos());
        check(root.mkdirs(), "create isolated test directory");
        testRoots.add(root);
        return new ContextWrapper(getTargetContext()) {
            @Override public File getFilesDir() { return root; }
            @Override public File getCacheDir() {
                File cache = new File(root, "cache");
                if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("Test cache unavailable");
                return cache;
            }
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.dataDir = root.getAbsolutePath();
                return info;
            }
            @Override public void sendBroadcast(Intent intent) {
                check(Constants.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())
                        && Constants.SYSTEM_UI_PACKAGE.equals(intent.getPackage())
                        && intent.getExtras() == null, "revocation contains no authorization or features");
                configurationSignals.incrementAndGet();
                // Synthetic storage tests must never affect a live SystemUI session.
            }
        };
    }

    private void roundTripAndConcurrentUpdates(Context context) throws Exception {
        IdentityModel model = syntheticModel();
        check(TemplateStore.saveIdentityModel(context, model), "save model");
        TemplateStore.RecognitionSettings settings = TemplateStore.loadRecognitionSettings(context);
        check(settings.model != null && settings.state.enabled && settings.state.enrolled,
                "single-read model and settings");
        check(settings.model.evaluate(model.textureCentroid, model.geometryCentroid,
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
        check(TemplateStore.trySetEnabled(context, true), "enable before grant tests");
        check(TemplateStore.authorizeUnlockSession(context, token), "authorize session");
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, wrong), "wrong grant rejected");
        check(TemplateStore.consumeAuthorizedUnlockSession(context, token), "wrong token kept real grant");
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, token), "grant is single use");
        File grant = new File(context.getFilesDir(), "unlock.session");
        try (FileOutputStream out = new FileOutputStream(grant)) {
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            out.write(("2\n" + token + "\n" + (System.currentTimeMillis() - 20_000L) + "\n"
                    + Math.max(0L, SystemClock.elapsedRealtime() - 20_000L) + "\n" + boot)
                    .getBytes(StandardCharsets.UTF_8));
        }
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, token), "expired grant rejected");
        check(!grant.exists(), "expired grant discarded");
        check(TemplateStore.authorizeUnlockSession(context, token), "authorize concurrent grant");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> consumers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread consumer = new Thread(() -> {
                try {
                    start.await();
                    if (TemplateStore.consumeAuthorizedUnlockSession(context, token)) accepted.incrementAndGet();
                } catch (Throwable error) { failure.set(error); }
            });
            consumers.add(consumer);
            consumer.start();
        }
        start.countDown();
        for (Thread consumer : consumers) consumer.join(10_000L);
        for (Thread consumer : consumers) check(!consumer.isAlive(), "grant consumer completed");
        check(failure.get() == null && accepted.get() == 1, "exactly one concurrent grant consumer accepted");
    }

    private void settingsRevocation(Context context) {
        TemplateStore.RecognitionSettings initial = TemplateStore.loadRecognitionSettings(context);
        check(TemplateStore.isRecognitionSettingsCurrent(context, initial), "fresh settings accepted");
        long revision = initial.state.revision;
        check(TemplateStore.tryRecordFailure(context), "record one failure");
        check(TemplateStore.readState(context).revision == revision, "counter does not revoke model revision");
        check(TemplateStore.validateAndRecordSuccess(context, initial), "atomic success clears counters");
        check(TemplateStore.readState(context).revision == revision, "successful result retains expected revision");
        int signals = configurationSignals.get();
        check(TemplateStore.trySetEnabled(context, true), "same enabled value succeeds");
        check(configurationSignals.get() == signals, "unchanged setting emits no revocation");
        String token = "synthetic-session-token-00000000000001";
        check(TemplateStore.authorizeUnlockSession(context, token), "grant before disabled setting");
        check(TemplateStore.trySetEnabled(context, false), "disable module");
        check(!TemplateStore.isRecognitionSettingsCurrent(context, initial), "disabled settings revoke old snapshot");
        check(!TemplateStore.validateAndRecordSuccess(context, initial), "old snapshot cannot commit success");
        check(!TemplateStore.consumeAuthorizedUnlockSession(context, token), "disable revokes pending grant");
        check(!TemplateStore.authorizeUnlockSession(context, token), "disabled module cannot authorize grant");
        check(configurationSignals.get() == signals + 1, "configuration change signals after commit");
        check(TemplateStore.trySetEnabled(context, true), "restore enabled state");
        TemplateStore.RecognitionSettings relocked = TemplateStore.loadRecognitionSettings(context);
        for (int i = 0; i < 5; i++) check(TemplateStore.tryRecordFailure(context), "record lockout failure");
        check(!TemplateStore.validateAndRecordSuccess(context, relocked), "new lockout revokes in-flight success");
        check(!TemplateStore.authorizeUnlockSession(context, token), "locked module cannot authorize grant");
        check(TemplateStore.tryRecordSuccess(context), "reset synthetic failure fixture");
    }

    private void combinedUnlockStartup() throws Exception {
        Context context = isolatedContext();
        IdentityModel model = syntheticModel();
        String token = "synthetic-combined-session-00000000000001";
        String wrong = "synthetic-combined-session-00000000000002";
        check(TemplateStore.saveIdentityModel(context, model), "combined startup enrollment");
        TemplateStore.UnlockPreparation prepared = TemplateStore.prepareUnlockSession(context, token);
        check(prepared.authorized && prepared.state.enabled && prepared.state.enrolled,
                "atomic preparation publishes usable state and grant");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, wrong) == null,
                "combined startup rejects wrong token without returning a model");
        TemplateStore.RecognitionSettings settings =
                TemplateStore.consumeAuthorizedRecognitionSettings(context, token);
        check(settings != null && settings.state.revision == prepared.state.revision
                && settings.model.evaluate(model.textureCentroid, model.geometryCentroid,
                        settings.threshold).accepted,
                "consumption returns the same validated identity and configuration");
        check(!new File(context.getFilesDir(), "unlock.session").exists(),
                "durable grant deletion precedes settings return");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) == null,
                "combined snapshot cannot be replayed");

        check(TemplateStore.prepareUnlockSession(context, token).authorized,
                "new provider can authorize an old capture client");
        check(TemplateStore.consumeAuthorizedUnlockSession(context, token), "old consumer remains compatible");
        check(TemplateStore.authorizeUnlockSession(context, token), "old provider authorizes new capture client");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) != null,
                "new consumer remains compatible");

        check(TemplateStore.prepareUnlockSession(context, token).authorized, "grant before configuration change");
        check(TemplateStore.trySetRecognitionProfile(context, 0), "change synthetic profile");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) == null,
                "configuration change revokes combined startup");
        check(TemplateStore.trySetEnabled(context, false), "disable combined startup fixture");
        check(!TemplateStore.prepareUnlockSession(context, token).authorized, "disabled preparation rejects launch");
        check(TemplateStore.trySetEnabled(context, true), "restore combined fixture");
        for (int i = 0; i < 5; i++) check(TemplateStore.tryRecordFailure(context), "combined fixture lockout");
        check(!TemplateStore.prepareUnlockSession(context, token).authorized, "lockout rejects preparation");
        check(TemplateStore.tryRecordSuccess(context), "reset combined fixture lockout");

        check(TemplateStore.prepareUnlockSession(context, token).authorized, "grant for concurrent snapshot consumption");
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> consumers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread consumer = new Thread(() -> {
                try {
                    start.await();
                    if (TemplateStore.consumeAuthorizedRecognitionSettings(context, token) != null) {
                        accepted.incrementAndGet();
                    }
                } catch (Throwable error) { failure.set(error); }
            });
            consumers.add(consumer);
            consumer.start();
        }
        start.countDown();
        for (Thread consumer : consumers) consumer.join(10_000L);
        for (Thread consumer : consumers) check(!consumer.isAlive(), "combined consumer completed");
        check(failure.get() == null && accepted.get() == 1, "exactly one combined snapshot returned");

        check(TemplateStore.prepareUnlockSession(context, token).authorized, "grant before model corruption");
        Properties corrupt = properties(context);
        corrupt.setProperty("geometry_threshold", "Infinity");
        AtomicStoreFiles.writeProperties(context.getFilesDir(), corrupt);
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) == null,
                "corruption after grant cannot return recognition settings");
        check(!new File(context.getFilesDir(), "unlock.session").exists(), "corrupt snapshot grant cannot replay");
        check(!TemplateStore.prepareUnlockSession(context, token).authorized, "corrupt model cannot prepare");
    }

    private void malformedModels(Context context) throws Exception {
        File base = new File(context.getFilesDir(), "face2d.properties");
        Properties props = new Properties();
        try (FileInputStream in = new AtomicFile(base).openRead()) { props.load(in); }
        props.setProperty("geometry_threshold", "Infinity");
        try (FileOutputStream out = new FileOutputStream(base)) { props.store(out, "synthetic"); }
        check(TemplateStore.loadIdentityModel(context) == null, "invalid model threshold rejected");
        check(!TemplateStore.readState(context).enrolled && !TemplateStore.readState(context).enabled
                && !TemplateStore.readState(context).storageAvailable, "corrupt model is unavailable and disabled");
        check(!TemplateStore.trySetEnabled(context, true), "corrupt model cannot be enabled");
        check(TemplateStore.saveIdentityModel(context, syntheticModel()), "restore synthetic model");
        TemplateStore.clearTemplates(context);
        check(TemplateStore.loadIdentityModel(context) == null && !TemplateStore.isEnabled(context),
                "clear disables and removes model");
        check(TemplateStore.getRecognitionProfile(context) == 1
                && TemplateStore.getAnimationStyle(context) == 1, "clear preserves preferences");
    }

    private void legacyMigration() throws Exception {
        Context context = isolatedContext();
        Properties original = new Properties();
        check(IdentityModelCodec.encodeInto(original, syntheticModel()), "prepare legacy v3 fixture");
        original.remove("identity_checksum");
        original.setProperty("enabled", "true");
        original.setProperty("recognition_profile", "0");
        original.setProperty("animation_style", "1");
        File prefs = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        check(prefs.mkdirs(), "create legacy preferences fixture");
        File legacy = new File(prefs, "face2d.xml");
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><map>");
        for (String key : original.stringPropertyNames()) {
            String value = original.getProperty(key);
            if ("enabled".equals(key)) {
                xml.append("<boolean name=\"").append(key).append("\" value=\"").append(value).append("\" />");
            } else {
                xml.append("<string name=\"").append(key).append("\">")
                        .append(value).append("</string>");
            }
        }
        xml.append("</map>");
        try (FileOutputStream out = new FileOutputStream(legacy)) {
            out.write(xml.toString().getBytes(StandardCharsets.UTF_8));
        }
        check(legacy.renameTo(new File(prefs, "face2d.xml.bak")), "simulate interrupted legacy preference write");
        check(TemplateStore.isEnabled(context) && TemplateStore.isEnrolled(context), "legacy v3 migrated intact");
        check(TemplateStore.getRecognitionProfile(context) == 0
                && TemplateStore.getAnimationStyle(context) == 1, "legacy preferences preserved");
        Properties migrated = properties(context);
        check(original.equals(migrated), "migration preserves exact v3 properties without forced reenrollment");
        check(TemplateStore.trySetAnimationStyle(context, 0), "edit migrated preferences");
        check(original.getProperty("template_0").equals(properties(context).getProperty("template_0")),
                "settings edit preserves original v3 feature bytes");
        check(TemplateStore.tryClearTemplates(context) && !legacy.exists(), "clear removes migrated biometric copy");
    }

    private void corruptFilePreservation() throws Exception {
        for (byte[] invalid : new byte[][]{
                "enabled=true\nenabled=false\n".getBytes(StandardCharsets.UTF_8),
                "enabled=true\ninvalid=\\uBROKEN\n".getBytes(StandardCharsets.UTF_8),
                new byte[AtomicStoreFiles.MAX_STORE_BYTES + 1]}) {
            Context context = isolatedContext();
            File base = new File(context.getFilesDir(), "face2d.properties");
            try (FileOutputStream out = new FileOutputStream(base)) { out.write(invalid); }
            check(!TemplateStore.readState(context).storageAvailable, "unreadable store fails closed");
            check(!TemplateStore.trySetAnimationStyle(context, 1), "unreadable store refuses settings overwrite");
            byte[] preserved;
            try (FileInputStream in = new FileInputStream(base)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                preserved = bytes.toByteArray();
            }
            check(java.util.Arrays.equals(invalid, preserved), "damaged original bytes retained for recovery");
        }
    }

    private void historicalBackupDeletion() throws Exception {
        Context context = isolatedContext();
        check(TemplateStore.saveIdentityModel(context, syntheticModel()), "historical cleanup synthetic enrollment");
        File backup = new File(context.getFilesDir(), "face2d-v2-before-v080.properties");
        File atomicBackup = new File(context.getFilesDir(), "face2d-v2-before-v080.properties.bak");
        File pendingBackup = new File(context.getFilesDir(), "face2d-v2-before-v080.properties.new");
        File unrelated = new File(context.getFilesDir(), "unrelated-notes.properties");
        File similarlyNamed = new File(context.getFilesDir(), "face2d-v2-before-v080.properties.copy");
        byte[] synthetic = "synthetic-old-face-data".getBytes(StandardCharsets.UTF_8);
        for (File file : new File[]{backup, atomicBackup, pendingBackup, unrelated, similarlyNamed}) {
            try (FileOutputStream out = new FileOutputStream(file)) { out.write(synthetic); }
        }
        check(TemplateStore.trySetAnimationStyle(context, 1), "ordinary preference update succeeds");
        check(backup.exists() && atomicBackup.exists() && pendingBackup.exists(),
                "ordinary preference update preserves historical biometric backup");
        check(TemplateStore.tryClearTemplates(context), "explicit template clear with historical backup succeeds");
        check(!backup.exists() && !atomicBackup.exists() && !pendingBackup.exists(),
                "clear removes exact known historical biometric backup and atomic copies");
        check(unrelated.exists() && similarlyNamed.exists(), "historical cleanup preserves unrelated files");
        check(TemplateStore.loadIdentityModel(context) == null && !TemplateStore.isEnabled(context),
                "historical cleanup completes normal template clear");
    }

    private void providerProtocol() throws Exception {
        Context context = isolatedContext();
        check(TemplateStore.saveIdentityModel(context, syntheticModel()), "provider synthetic enrollment");
        ConfigProvider provider = new ConfigProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "com.yusd.pixel2dface.config";
        provider.attachInfo(context, info);
        Bundle state = provider.call("state", null, null);
        check(state.size() == 6 && state.containsKey("enabled") && state.containsKey("enrolled")
                && state.containsKey("lockout_until") && state.containsKey("animation_style")
                && state.containsKey("storage_available") && state.containsKey("revision"),
                "provider returns only allowlisted non-biometric state");
        Bundle bad = new Bundle();
        bad.putString("platform", "injected\nreport");
        expectInvalid(() -> provider.call("heartbeat", null, bad), "newline diagnostics rejected");
        bad.clear();
        bad.putString("platform", new String(new char[161]).replace('\0', 'x'));
        expectInvalid(() -> provider.call("heartbeat", null, bad), "oversized diagnostics rejected");
        bad.clear();
        bad.putString("wake_method", "true");
        expectInvalid(() -> provider.call("heartbeat", null, bad), "incorrect diagnostics type rejected");
        expectInvalid(() -> provider.call("state", "extra", null), "unexpected state argument rejected");
        expectInvalid(() -> provider.call("model", null, null), "biometric method unavailable");
        Bundle heartbeat = new Bundle();
        heartbeat.putString("platform", "Synthetic Android");
        heartbeat.putBoolean("wake_method", true);
        check(provider.call("heartbeat", null, heartbeat).getBoolean("ok"), "valid heartbeat persisted");
        check(TemplateStore.getHookCompatibility(context).contains("Synthetic Android"), "bounded compatibility readable");
        check(!provider.call("authorize_unlock", "short", null).getBoolean("ok"), "bad authorization token rejected");
        Bundle prepared = provider.call("prepare_unlock", "synthetic-provider-token-00000000000001", null);
        check(prepared.size() == 7 && prepared.keySet().containsAll(state.keySet())
                && prepared.getBoolean("ok"), "combined provider returns only state and authorization status");
        check(!provider.call("prepare_unlock", "short", null).getBoolean("ok"), "combined provider rejects bad token");
        expectInvalid(() -> provider.call("prepare_unlock", "synthetic-provider-token-00000000000001", bad),
                "preparation rejects unexpected extras");
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
        check(!TemplateStore.trySetAnimationStyle(context, 1), "settings failure reported");
        check(!TemplateStore.recordHookHeartbeat(context), "heartbeat failure reported");
        check(!TemplateStore.readState(context).storageAvailable, "storage failure exposed without enrollment");
        check(!TemplateStore.prepareUnlockSession(context, "synthetic-write-token-00000000000001").authorized,
                "failed preparation cannot report a grant");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context,
                "synthetic-write-token-00000000000001") == null, "failed consume cannot expose settings");
    }

    private Properties properties(Context context) throws Exception {
        Properties result = new Properties();
        try (FileInputStream in = new AtomicFile(new File(context.getFilesDir(), "face2d.properties")).openRead()) {
            result.load(in);
        }
        return result;
    }

    private void neuralStorage() throws Exception {
        Context context = isolatedContext();
        check(TemplateStore.saveIdentityModel(context, syntheticModel()), "seed compatible v3");
        TemplateStore.RecognitionSettings old = TemplateStore.loadRecognitionSettings(context);
        float[] vector = new float[192]; vector[0] = 1;
        check(TemplateStore.saveIdentityModel(context, new EmbeddingIdentity(vector, .24f)), "save v4");
        TemplateStore.RecognitionSettings settings = TemplateStore.loadRecognitionSettings(context);
        check(settings.state.enabled && settings.state.neuralTemplate && settings.model instanceof EmbeddingIdentity,
                "v4 snapshot selects neural backend");
        check(!TemplateStore.isRecognitionSettingsCurrent(context, old), "v3 in-flight identity revoked");
        check(settings.model.evaluate(vector, null, settings.threshold).accepted, "v4 disk identity matches");
        check(!properties(context).containsKey("template_0"), "v3 features removed from new template");
        String token = "synthetic-neural-token-00000000000001";
        check(TemplateStore.prepareUnlockSession(context, token).authorized, "v4 grant prepared");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token).model instanceof EmbeddingIdentity,
                "v4 grant returns neural template");
        check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) == null, "v4 grant not replayable");
        Properties corrupt = properties(context);
        corrupt.setProperty("embedding_model", "unknown-model");
        corrupt.setProperty("identity_checksum", TemplateCodec.fingerprint(corrupt));
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "face2d.properties"))) {
            corrupt.store(out, "synthetic");
        }
        check(!TemplateStore.readState(context).storageAvailable, "unsupported embedding cannot fall back");
        check(!TemplateStore.validateAndRecordSuccess(context, settings), "changed embedding cannot authenticate");
        check(TemplateStore.tryClearTemplates(context), "clear malformed v4");
        check(!properties(context).containsKey("embedding_centroid"), "clear erases neural identity");
    }

    private void verifiedEnrollmentTransfer() throws Exception {
        for (boolean enabled : new boolean[]{true, false}) {
            Context context = isolatedContext();
            check(TemplateStore.saveIdentityModel(context, syntheticModel()), "seed transfer target");
            check(TemplateStore.trySetRecognitionProfile(context, 2), "set original transfer preference");
            check(TemplateStore.tryRecordFailure(context), "preserve prior failure state");
            String token = "synthetic-transfer-token-00000000000001";
            check(TemplateStore.prepareUnlockSession(context, token).authorized, "prepare old grant");
            check(TemplateStore.trySetEnabled(context, enabled), "set original enabled state");
            Properties original = properties(context);
            byte[] originalBytes = AtomicStoreFiles.readBytes(new File(context.getFilesDir(), AtomicStoreFiles.STORE_FILE),
                    AtomicStoreFiles.MAX_STORE_BYTES);
            String targetHash = EnrollmentTransferInstrumentation.hash(originalBytes);
            File staging = new File(context.getCacheDir(), "verified-enrollment-transfer");
            check(staging.mkdir(), "create synthetic transfer stage");
            Properties source = new Properties();
            float[] vector = new float[192]; vector[0] = 1;
            check(TemplateCodec.encodeInto(source, new EmbeddingIdentity(vector, .24f)), "encode synthetic transfer identity");
            AtomicStoreFiles.writeProperties(staging, source);
            File sourceFile = new File(staging, AtomicStoreFiles.STORE_FILE);
            String sourceHash = EnrollmentTransferInstrumentation.hash(AtomicStoreFiles.readBytes(sourceFile,
                    AtomicStoreFiles.MAX_STORE_BYTES));
            String wrongHash = new String(new char[64]).replace('\0', '0');
            expectTransferFailure(context, wrongHash, targetHash, "changed source rejected");
            expectTransferFailure(context, sourceHash, wrongHash, "changed target rejected");
            check(java.util.Arrays.equals(originalBytes, AtomicStoreFiles.readBytes(
                    new File(context.getFilesDir(), AtomicStoreFiles.STORE_FILE), AtomicStoreFiles.MAX_STORE_BYTES)),
                    "rejected transfer preserves target bytes");
            Properties invalid = new Properties(); invalid.putAll(source);
            invalid.setProperty("embedding_model", "other-model");
            invalid.setProperty("identity_checksum", TemplateCodec.fingerprint(invalid));
            AtomicStoreFiles.writeProperties(staging, invalid);
            String invalidHash = EnrollmentTransferInstrumentation.hash(AtomicStoreFiles.readBytes(sourceFile,
                    AtomicStoreFiles.MAX_STORE_BYTES));
            expectTransferFailure(context, invalidHash, targetHash, "unknown model rejected despite valid file hash");
            AtomicStoreFiles.writeProperties(staging, source);
            sourceHash = EnrollmentTransferInstrumentation.hash(AtomicStoreFiles.readBytes(sourceFile,
                    AtomicStoreFiles.MAX_STORE_BYTES));
            EnrollmentTransferInstrumentation.transfer(context, sourceHash, targetHash);
            Properties after = properties(context);
            check(TemplateCodec.decode(after) instanceof EmbeddingIdentity, "transfer selects neural backend");
            check(TemplateCodec.fingerprint(source).equals(TemplateCodec.fingerprint(after)), "transferred identity preserved");
            check(TemplateStore.isEnabled(context) == enabled, "enabled state preserved");
            check(TemplateStore.getRecognitionProfile(context) == 2 && "1".equals(after.getProperty("failures")),
                    "preferences and failures preserved");
            check(StorePolicy.longValue(after, "store_revision", -1) == StorePolicy.longValue(original, "store_revision", -1) + 1,
                    "transfer increments revision once");
            check(TemplateStore.consumeAuthorizedRecognitionSettings(context, token) == null, "old grant revoked");
            check(!staging.exists(), "transfer staging removed");
        }
    }

    private void expectTransferFailure(Context context, String sourceHash, String targetHash, String message) {
        boolean rejected = false;
        try { EnrollmentTransferInstrumentation.transfer(context, sourceHash, targetHash); }
        catch (java.io.IOException expected) { rejected = true; }
        check(rejected, message);
    }

    private void verifiedLegacyRollback() throws Exception {
        Context context = isolatedContext();
        float[] vector = new float[192]; vector[0] = 1;
        check(TemplateStore.saveIdentityModel(context, new EmbeddingIdentity(vector, .24f)), "seed rollback target");
        check(TemplateStore.trySetRecognitionProfile(context, 0), "preserve rollback profile");
        File stage = new File(context.getCacheDir(), "verified-enrollment-transfer");
        check(stage.mkdir(), "create rollback stage");
        Properties original = new Properties();
        check(IdentityModelCodec.encodeInto(original, syntheticModel()), "encode prior v3 identity");
        AtomicStoreFiles.writeProperties(stage, original);
        String sourceHash = EnrollmentTransferInstrumentation.hash(AtomicStoreFiles.readBytes(
                new File(stage, AtomicStoreFiles.STORE_FILE), AtomicStoreFiles.MAX_STORE_BYTES));
        String targetHash = EnrollmentTransferInstrumentation.hash(AtomicStoreFiles.readBytes(
                new File(context.getFilesDir(), AtomicStoreFiles.STORE_FILE), AtomicStoreFiles.MAX_STORE_BYTES));
        expectTransferFailure(context, sourceHash, targetHash, "v3 downgrade needs explicit rollback option");
        EnrollmentTransferInstrumentation.transfer(context, sourceHash, targetHash, true);
        check(TemplateStore.loadIdentityModel(context) instanceof IdentityModel, "rollback restores original backend");
        check(TemplateStore.isEnabled(context) && TemplateStore.getRecognitionProfile(context) == 0,
                "rollback preserves preferences");
        check(TemplateCodec.fingerprint(properties(context)).equals(TemplateCodec.fingerprint(original)),
                "rollback restores exact original identity");
        check(!stage.exists(), "rollback removes staging");
    }

    private void expectInvalid(Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, message);
    }

    private void cleanup() {
        for (File root : testRoots) deleteTestTree(root);
    }

    private void deleteTestTree(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTestTree(child);
        file.delete();
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
        checks.incrementAndGet();
    }
}

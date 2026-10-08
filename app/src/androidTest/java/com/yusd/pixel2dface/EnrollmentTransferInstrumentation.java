package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;

/** One-time, same-signature maintenance tool. Never packaged in the production APK. */
public final class EnrollmentTransferInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            require("com.yusd.pixel2dface".equals(context.getPackageName()), "Wrong target package");
            require(arguments != null && "true".equals(arguments.getString("allow_import")), "Import not requested");
            String expectedSource = arguments.getString("source_sha256");
            String expectedTarget = arguments.getString("target_sha256");
            boolean rollback = "true".equals(arguments.getString("rollback_v3"));
            transfer(context, expectedSource, expectedTarget, rollback);
            result.putString("result", "PASS");
            result.putBoolean("identityPreserved", true);
            result.putBoolean("settingsPreserved", true);
            result.putBoolean("grantsRevoked", true);
            result.putString("modelId", rollback ? "legacy-v3" : EmbeddingIdentity.MODEL_ID);
            finish(Activity.RESULT_OK, result);
        } catch (Exception failure) {
            result.putString("result", "FAIL: " + failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    static void transfer(Context context, String expectedSource, String expectedTarget) throws IOException {
        transfer(context, expectedSource, expectedTarget, false);
    }

    static void transfer(Context context, String expectedSource, String expectedTarget, boolean rollback) throws IOException {
        require(validHash(expectedSource) && validHash(expectedTarget), "Expected hashes required");
        File staging = new File(context.getCacheDir(), "verified-enrollment-transfer");
        File sourceFile = new File(staging, AtomicStoreFiles.STORE_FILE);
        require(staging.getCanonicalFile().getParentFile().equals(context.getCacheDir().getCanonicalFile()),
                "Invalid staging directory");
        byte[] sourceBytes = AtomicStoreFiles.readBytes(sourceFile, AtomicStoreFiles.MAX_STORE_BYTES);
        require(sourceBytes != null && expectedSource.equals(hash(sourceBytes)), "Source hash mismatch");
        Properties source = AtomicStoreFiles.readProperties(context, staging);
        // The staged input must stay unchanged across parsing, including its strict duplicate-key checks.
        require(expectedSource.equals(hash(AtomicStoreFiles.readBytes(sourceFile, AtomicStoreFiles.MAX_STORE_BYTES))),
                "Source changed while reading");
        IdentityTemplate identity = TemplateCodec.decode(source);
        require(rollback ? identity instanceof IdentityModel : identity instanceof EmbeddingIdentity,
                "Unexpected enrollment version");
        String sourceFingerprint = TemplateCodec.fingerprint(source);
        boolean imported = AtomicStoreFiles.locked(context, false, directory -> {
            byte[] before = AtomicStoreFiles.readBytes(new File(directory, AtomicStoreFiles.STORE_FILE),
                    AtomicStoreFiles.MAX_STORE_BYTES);
            require(before != null && expectedTarget.equals(hash(before)), "Target changed since backup");
            Properties target = AtomicStoreFiles.readProperties(context, directory);
            TemplateStore.StateSnapshot original = new TemplateStore.StateSnapshot(target);
            require(original.storageAvailable && original.enrolled, "Valid original enrollment required");
            require(original.revision >= 0 && original.revision < Long.MAX_VALUE, "Invalid revision");
            Properties preserved = new Properties(); preserved.putAll(target);
            TemplateCodec.clear(preserved); preserved.remove("store_revision");
            require(TemplateCodec.encodeInto(target, identity), "Unable to encode enrollment");
            target.setProperty("store_revision", Long.toString(original.revision + 1));
            Properties afterSettings = new Properties(); afterSettings.putAll(target);
            TemplateCodec.clear(afterSettings); afterSettings.remove("store_revision");
            require(preserved.equals(afterSettings), "Non-biometric settings changed");
            require(sourceFingerprint.equals(TemplateCodec.fingerprint(target)), "Identity changed during transfer");
            HookRuntimeStore.invalidate(directory);
            AtomicStoreFiles.writeProperties(directory, target);
            TemplateStore.StateSnapshot after = new TemplateStore.StateSnapshot(target);
            require(after.storageAvailable && after.neuralTemplate == !rollback && after.enabled == original.enabled,
                    "Unexpected imported state");
            return true;
        });
        require(imported, "Atomic transfer failed; inspect before retrying");
        context.sendBroadcast(new Intent(Constants.ACTION_CONFIGURATION_CHANGED)
                .setPackage(Constants.SYSTEM_UI_PACKAGE));
        AtomicStoreFiles.delete(sourceFile);
        require(staging.delete(), "Staging directory cleanup failed");
    }

    private static void require(boolean value, String message) throws IOException {
        if (!value) throw new IOException(message);
    }
    private static boolean validHash(String hash) { return hash != null && hash.matches("[0-9a-f]{64}"); }
    static String hash(byte[] bytes) throws IOException {
        if (bytes == null) throw new IOException("Missing data");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
}

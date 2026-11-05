package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import android.content.Context;
import android.provider.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = AtomicFileHostShadow.class)
public final class CorruptStoreClearTest {
    private Context context;
    private File store;

    @Before public void storage() {
        context = RuntimeEnvironment.getApplication();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 1);
        store = new File(context.getFilesDir(), AtomicStoreFiles.STORE_FILE);
    }

    @Test public void malformedCanonicalDataIsPreservedUntilExplicitClear() throws Exception {
        for (String source : new String[]{"broken=\\uBAD!", "enabled=true\nenabled=false",
                "x".repeat(129) + "=bad", manyProperties(),
                "x=" + "x".repeat(AtomicStoreFiles.MAX_STORE_BYTES)}) {
            byte[] corrupt = source.getBytes(StandardCharsets.UTF_8);
            Files.write(store.toPath(), corrupt);
            assertFalse(TemplateStore.readState(context).storageAvailable);
            assertFalse(TemplateStore.trySetAnimationStyle(context, 1));
            assertFalse(TemplateStore.saveIdentityModel(context, model()));
            assertArrayEquals(corrupt, Files.readAllBytes(store.toPath()));
            assertTrue("explicit clear must recover " + source.substring(0, 10),
                    TemplateStore.tryClearTemplates(context));
            assertCleared();
            assertTrue(TemplateStore.saveIdentityModel(context, model()));
        }
    }

    @Test public void malformedLegacyDataCanBeExplicitlyDeleted() throws Exception {
        File legacy = new File(context.getApplicationInfo().dataDir, "shared_prefs/face2d.xml");
        Files.createDirectories(legacy.toPath().getParent());
        Files.write(legacy.toPath(), "<map><string name=\"template_0\">broken".getBytes(StandardCharsets.UTF_8));
        assertFalse(TemplateStore.readState(context).storageAvailable);
        assertTrue(TemplateStore.tryClearTemplates(context));
        assertFalse(legacy.exists());
        assertCleared();
    }

    @Test public void inaccessibleCanonicalPathStillFailsWithoutDiscardingData() throws Exception {
        Files.createDirectory(store.toPath());
        File child = new File(store, "keep");
        Files.write(child.toPath(), "untouched".getBytes(StandardCharsets.UTF_8));
        assertFalse(TemplateStore.tryClearTemplates(context));
        assertEquals("untouched", new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8));
        assertTrue(store.isDirectory());
    }

    @Test public void validPreferencesSurviveClearAndOutstandingGrantsAreRevoked() throws Exception {
        assertTrue(TemplateStore.saveIdentityModel(context, model()));
        assertTrue(TemplateStore.trySetAnimationStyle(context, 1));
        assertTrue(TemplateStore.trySetRecognitionProfile(context, 0));
        String token = "0123456789abcdef0123456789abcdef";
        assertTrue(TemplateStore.prepareUnlockSession(context, token).authorized);
        long revision = TemplateStore.readState(context).revision;
        assertTrue(TemplateStore.tryClearTemplates(context));
        assertCleared();
        assertEquals(1, TemplateStore.getAnimationStyle(context));
        assertEquals(0, TemplateStore.getRecognitionProfile(context));
        assertTrue(TemplateStore.readState(context).revision > revision);
        assertTrue(TemplateStore.saveIdentityModel(context, model()));
        assertNull(TemplateStore.consumeAuthorizedRecognitionSettings(context, token));
    }

    private void assertCleared() throws Exception {
        TemplateStore.StateSnapshot state = TemplateStore.readState(context);
        assertTrue(state.storageAvailable);
        assertFalse(state.enabled);
        assertFalse(state.enrolled);
        assertEquals(0, state.lockoutUntil);
        Properties properties = AtomicStoreFiles.readProperties(context, context.getFilesDir());
        assertFalse(properties.containsKey("embedding_centroid"));
        assertFalse(properties.containsKey("template_count"));
    }

    private static EmbeddingIdentity model() {
        return new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f);
    }

    private static String manyProperties() {
        StringBuilder source = new StringBuilder();
        for (int index = 0; index < 257; index++) source.append("key").append(index).append("=value\n");
        return source.toString();
    }
}

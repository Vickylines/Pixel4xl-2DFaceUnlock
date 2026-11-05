package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.Properties;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = AtomicFileHostShadow.class)
public final class RecognitionFailureStorageTest {
    private Context context;
    private TemplateStore.RecognitionSettings settings;

    @Before public void enrollment() {
        context = RuntimeEnvironment.getApplication();
        assertTrue(TemplateStore.saveIdentityModel(context,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(0), .22f)));
        settings = TemplateStore.loadRecognitionSettings(context);
    }

    @Test public void delayedFailureCannotPenalizeAReplacementEnrollment() throws Exception {
        assertTrue(TemplateStore.saveIdentityModel(context,
                new EmbeddingIdentity(EmbeddingIdentityTest.vector(1), .22f)));
        long revision = TemplateStore.readState(context).revision;
        for (int attempt = 0; attempt < 5; attempt++) {
            assertFalse(TemplateStore.validateAndRecordFailure(context, settings));
        }
        assertEquals(revision, TemplateStore.readState(context).revision);
        assertEquals("0", properties().getProperty("failures"));
        assertEquals(0, TemplateStore.getLockoutUntil(context));
    }

    @Test public void disabledOrChangedPolicyRejectsOldFailureAndSuccess() throws Exception {
        assertTrue(TemplateStore.trySetRecognitionProfile(context, 0));
        assertFalse(TemplateStore.validateAndRecordFailure(context, settings));
        assertFalse(TemplateStore.validateAndRecordSuccess(context, settings));
        settings = TemplateStore.loadRecognitionSettings(context);
        assertTrue(TemplateStore.trySetEnabled(context, false));
        assertFalse(TemplateStore.validateAndRecordFailure(context, settings));
        assertFalse(TemplateStore.validateAndRecordSuccess(context, settings));
        assertEquals("0", properties().getProperty("failures"));
    }

    @Test public void onlyInitializedCurrentRecognitionCountsTowardLockout() throws Exception {
        assertFalse(TemplateStore.validateAndRecordFailure(context, null));
        assertEquals("0", properties().getProperty("failures"));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertTrue(TemplateStore.validateAndRecordFailure(context, settings));
        }
        long lockout = TemplateStore.getLockoutUntil(context);
        assertTrue(lockout > System.currentTimeMillis());
        assertFalse(TemplateStore.validateAndRecordSuccess(context, settings));
        assertFalse(TemplateStore.validateAndRecordFailure(context, settings));
        assertEquals(lockout, TemplateStore.getLockoutUntil(context));
    }

    private Properties properties() throws Exception {
        return AtomicStoreFiles.readProperties(context, context.getFilesDir());
    }
}

package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

import android.content.Context;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class BackupExclusionTest {
    @Test public void cloudBackupExcludesEachStorageDomain() throws Exception {
        assertStorageExcluded("CLOUD");
    }

    @Test public void deviceTransferExcludesEachStorageDomain() throws Exception {
        assertStorageExcluded("DEVICE_TRANSFER");
    }

    private static void assertStorageExcluded(String transport) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setField(context.getApplicationInfo(), "dataExtractionRulesRes",
                R.xml.data_extraction_rules);
        Class<?> destinations = Class.forName("android.app.backup.BackupAnnotations$BackupDestination");
        int destination = ReflectionHelpers.getStaticField(destinations, transport);
        Object scheme = ReflectionHelpers.callStaticMethod(Class.forName("android.app.backup.FullBackup"),
                "getBackupScheme", from(Context.class, context), from(int.class, destination));
        Set<?> exclusions = ReflectionHelpers.callInstanceMethod(scheme,
                "maybeParseAndGetCanonicalExcludePaths");
        Set<String> paths = new HashSet<>();
        for (Object exclusion : exclusions) {
            paths.add(ReflectionHelpers.callInstanceMethod(exclusion, "getPath"));
        }
        // Android traverses these roots separately and tests exclusions by exact path.
        // Excluding the credential root alone does not prune the files/preferences passes.
        Context credentialStorage = ReflectionHelpers.callInstanceMethod(context,
                "createCredentialProtectedStorageContext");
        for (Context storage : new Context[]{credentialStorage,
                context.createDeviceProtectedStorageContext()}) {
            for (File directory : new File[]{storage.getDataDir(), storage.getFilesDir(),
                    storage.getDatabasePath("test.db").getParentFile(),
                    new File(storage.getDataDir(), "shared_prefs")}) {
                assertTrue(transport + " can visit " + directory.getCanonicalPath() + " with exclusions " + paths,
                        paths.contains(directory.getCanonicalPath()));
            }
        }
        assertTrue(paths.contains(context.getExternalFilesDir(null).getCanonicalPath()));
    }
}

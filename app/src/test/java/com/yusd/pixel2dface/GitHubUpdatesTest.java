package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GitHubUpdatesTest {
    private static final String TAG = "v0.13.34";
    private static final String APK = "Pixel2DFaceUnlock-universal-0.13.34.apk";
    private static final String URL = GitHubUpdates.RELEASES_URL + "/download/" + TAG + "/" + APK;

    @Test public void recognizesReleaseNumbersAndPreviouslyInstalledMotionSuffix() throws Exception {
        for (String current : new String[]{"0.13.9", "0.13.33-motion", "0.13.23-validation"})
            assertTrue(GitHubUpdates.parse(release(), current).newer);
        for (String current : new String[]{"0.13.34", "0.13.34-validation", "0.13.35", "0.14.0"})
            assertFalse(GitHubUpdates.parse(release(), current).newer);
    }

    @Test public void choosesTheOfficialUniversalApkAndShowsReleaseNotes() throws Exception {
        JSONArray assets = new JSONArray()
                .put(asset(APK, "https://example.org/" + APK))
                .put(asset("app-ci.apk", GitHubUpdates.RELEASES_URL + "/download/" + TAG + "/app-ci.apk"))
                .put(asset(APK, URL));
        GitHubUpdates.Release result = GitHubUpdates.parse(release().put("assets", assets), "0.13.33-motion");
        assertEquals("0.13.34", result.version);
        assertEquals(URL, result.downloadUrl);
        assertEquals(77900000L, result.bytes);
        assertEquals("锁屏动画更新", result.notes);
    }

    @Test public void incompleteAndUnexpectedAssetsFallBackToTheOfficialReleasePage() throws Exception {
        JSONArray assets = new JSONArray()
                .put(asset(APK, URL + "?redirect=1"))
                .put(asset(APK, URL).put("state", "new"))
                .put(asset(APK, URL).put("size", 0));
        GitHubUpdates.Release result = GitHubUpdates.parse(release().put("assets", assets), "0.13.33");
        assertTrue(result.newer);
        assertNull(result.downloadUrl);
        assertEquals(GitHubUpdates.RELEASES_URL + "/tag/" + TAG, result.pageUrl);
    }

    @Test public void acceptsTheMotionNamingUsedByEarlierPublicReleases() throws Exception {
        String apk = "Pixel2DFaceUnlock-universal-0.13.23-motion.apk";
        String url = GitHubUpdates.RELEASES_URL + "/download/v0.13.23/" + apk;
        JSONObject response = release().put("tag_name", "v0.13.23")
                .put("assets", new JSONArray().put(asset(apk, url)));
        assertEquals(url, GitHubUpdates.parse(response, "0.13.14-motion").downloadUrl);
    }

    @Test public void rejectsIncompleteReleaseMetadataInsteadOfReportingUpToDate() throws Exception {
        assertThrows(JSONException.class, () -> GitHubUpdates.parse(new JSONObject(), "0.13.33"));
        assertThrows(JSONException.class, () -> GitHubUpdates.parse(release().put("draft", true), "0.13.33"));
        assertThrows(JSONException.class, () -> GitHubUpdates.parse(release().put("prerelease", true), "0.13.33"));
        assertThrows(JSONException.class, () -> GitHubUpdates.parse(release().put("tag_name", "v0.13.34-rc1"), "0.13.33"));
    }

    private static JSONObject release() throws JSONException {
        return new JSONObject().put("tag_name", TAG).put("draft", false)
                .put("prerelease", false).put("body", "锁屏动画更新");
    }

    private static JSONObject asset(String name, String url) throws JSONException {
        return new JSONObject().put("name", name).put("browser_download_url", url)
                .put("state", "uploaded").put("size", 77900000L);
    }
}

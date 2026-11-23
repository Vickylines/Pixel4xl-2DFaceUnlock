package com.yusd.pixel2dface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads public release metadata only when the settings screen requests it. */
final class GitHubUpdates {
    private static final String REPOSITORY = "Vickylines/Pixel4xl-2DFaceUnlock";
    static final String RELEASES_URL = "https://github.com/" + REPOSITORY + "/releases";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final Pattern VERSION = Pattern.compile("([0-9]{1,9})\\.([0-9]{1,9})\\.([0-9]{1,9})");
    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_NOTES_CHARS = 1800;

    private GitHubUpdates() { }

    static Release check(String installedVersion) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_API).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Pixel4xl-2DFaceUnlock");
            int status = connection.getResponseCode();
            if (status == 429 || (status == 403 && ("0".equals(connection.getHeaderField("X-RateLimit-Remaining"))
                    || connection.getHeaderField("Retry-After") != null))) {
                throw new RateLimitedException();
            }
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub response " + status);
            try (InputStream input = connection.getInputStream()) {
                return parse(new JSONObject(readBody(input)), installedVersion);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1; ) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (output.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Release response too large");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    static Release parse(JSONObject response, String installedVersion) throws JSONException {
        if (response.getBoolean("draft") || response.getBoolean("prerelease"))
            throw new JSONException("Expected a published stable release");
        String tag = response.getString("tag_name");
        if (!tag.startsWith("v")) throw new JSONException("Invalid release tag");
        String version = tag.substring(1);
        boolean newer = compareVersion(version, installedVersion.split("-", 2)[0]) > 0;
        String notes = response.isNull("body") ? "" : response.optString("body", "").trim();
        if (notes.codePointCount(0, notes.length()) > MAX_NOTES_CHARS)
            notes = notes.substring(0, notes.offsetByCodePoints(0, MAX_NOTES_CHARS)).trim() + "\n…";
        String prefix = "Pixel2DFaceUnlock-universal-" + version;
        JSONArray assets = response.optJSONArray("assets");
        String downloadUrl = null;
        long bytes = 0;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !"uploaded".equals(asset.optString("state"))) continue;
            String name = asset.optString("name");
            boolean stableName = name.equals(prefix + ".apk");
            if (!stableName && !name.equals(prefix + "-motion.apk")) continue;
            String url = RELEASES_URL + "/download/" + tag + "/" + name;
            long size = asset.optLong("size", 0);
            if (size <= 0 || !url.equals(asset.optString("browser_download_url"))) continue;
            downloadUrl = url;
            bytes = size;
            if (stableName) break;
        }
        return new Release(version, newer, notes, RELEASES_URL + "/tag/" + tag, downloadUrl, bytes);
    }

    private static int compareVersion(String latest, String installed) throws JSONException {
        Matcher left = VERSION.matcher(latest);
        Matcher right = VERSION.matcher(installed);
        if (!left.matches() || !right.matches()) throw new JSONException("Invalid version");
        for (int part = 1; part <= 3; part++) {
            int compared = Integer.compare(Integer.parseInt(left.group(part)), Integer.parseInt(right.group(part)));
            if (compared != 0) return compared;
        }
        return 0;
    }

    static final class Release {
        final String version, notes, pageUrl, downloadUrl;
        final boolean newer;
        final long bytes;

        Release(String version, boolean newer, String notes, String pageUrl, String downloadUrl, long bytes) {
            this.version = version;
            this.newer = newer;
            this.notes = notes;
            this.pageUrl = pageUrl;
            this.downloadUrl = downloadUrl;
            this.bytes = bytes;
        }
    }

    static final class RateLimitedException extends IOException { }
}

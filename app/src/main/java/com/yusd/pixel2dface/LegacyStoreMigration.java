package com.yusd.pixel2dface;

import android.content.Context;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** One-time, bounded migration; an unreadable legacy store is never silently reset. */
final class LegacyStoreMigration {
    private LegacyStoreMigration() { }

    static void delete(Context context) throws IOException {
        File legacy = new File(new File(context.getApplicationInfo().dataDir,
                "shared_prefs"), "face2d.xml");
        AtomicStoreFiles.delete(legacy);
        // A prior local v0.8.0 migration retained this exact biometric backup.
        // Only an explicit user-requested template clear reaches this method.
        // Do not enumerate files or remove unrelated application data.
        for (String name : new String[]{"face2d-v2-before-v080.properties",
                "face2d-v2-before-v080.properties.bak", "face2d-v2-before-v080.properties.new"}) {
            File historical = new File(context.getFilesDir(), name);
            if (historical.exists() && !historical.delete()) {
                throw new IOException("Unable to delete known historical biometric backup");
            }
        }
    }

    static Properties read(Context context, int maximum) throws IOException {
        Properties properties = new Properties();
        File legacy = new File(new File(context.getApplicationInfo().dataDir,
                "shared_prefs"), "face2d.xml");
        byte[] bytes = AtomicStoreFiles.readBytes(legacy, maximum);
        if (bytes == null) return properties;
        String source = new String(bytes, StandardCharsets.UTF_8);
        if (source.contains("<!DOCTYPE") || source.contains("<!ENTITY")) {
            throw new IOException("Legacy declarations are not allowed");
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new ByteArrayInputStream(bytes), "UTF-8");
            boolean foundRoot = false;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG) continue;
                if (!foundRoot) {
                    if (!"map".equals(parser.getName()) || parser.getDepth() != 1) {
                        throw new IOException("Invalid legacy root");
                    }
                    foundRoot = true;
                    continue;
                }
                if (parser.getDepth() != 2) throw new IOException("Invalid legacy structure");
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                if (name == null || name.isEmpty() || properties.containsKey(name)) {
                    throw new IOException("Invalid legacy key");
                }
                String value;
                if ("string".equals(tag)) {
                    value = parser.nextText();
                } else if ("boolean".equals(tag) || "int".equals(tag)
                        || "long".equals(tag) || "float".equals(tag)) {
                    value = parser.getAttributeValue(null, "value");
                } else {
                    throw new IOException("Unsupported legacy field");
                }
                if (value == null) throw new IOException("Missing legacy value");
                properties.setProperty(name, value);
            }
            if (!foundRoot) throw new IOException("Missing legacy root");
            return properties;
        } catch (XmlPullParserException | RuntimeException error) {
            throw new IOException("Invalid legacy store");
        }
    }
}

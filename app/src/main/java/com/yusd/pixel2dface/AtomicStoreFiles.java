package com.yusd.pixel2dface;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * All reads, read/modify/write transactions, AtomicFile recovery, and one-time grant
 * consumption use the same lock. The file lock also covers another app process.
 */
final class AtomicStoreFiles {
    static final String STORE_FILE = "face2d.properties";
    static final int MAX_STORE_BYTES = 128 * 1024;
    private static final int MAX_PROPERTIES = 256;
    private static final Object PROCESS_LOCK = new Object();

    private AtomicStoreFiles() { }

    interface Operation<T> { T run(File directory) throws IOException; }

    static <T> T locked(Context context, T fallback, Operation<T> operation) {
        synchronized (PROCESS_LOCK) {
            try {
                File directory = context.getFilesDir();
                if (directory == null || (!directory.isDirectory()
                        && !directory.mkdirs())) {
                    return fallback;
                }
                // Never delete this file: replacing its inode would split cross-process locks.
                File mutex = new File(directory, "face2d.store.lock");
                try (RandomAccessFile file = new RandomAccessFile(mutex, "rw");
                        FileChannel channel = file.getChannel();
                        FileLock ignored = channel.lock()) {
                    return operation.run(directory);
                }
            } catch (IOException | RuntimeException error) {
                // Exception messages can contain stored material. Only expose the category.
                Log.e("Pixel2DFace", "Storage operation failed ("
                        + error.getClass().getSimpleName() + ")");
                return fallback;
            }
        }
    }

    static Properties readProperties(Context context, File directory) throws IOException {
        File file = new File(directory, STORE_FILE);
        byte[] bytes = readBytes(file, MAX_STORE_BYTES);
        if (bytes == null) {
            Properties migrated = LegacyStoreMigration.read(context, MAX_STORE_BYTES);
            if (!migrated.isEmpty()) {
                // Failed migration persistence is not reported as a usable enrollment.
                writeProperties(directory, migrated);
            }
            return migrated;
        }
        Properties properties = new UniqueProperties();
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            properties.load(input);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid property syntax");
        }
        validateProperties(properties);
        Properties mutable = new Properties();
        mutable.putAll(properties);
        return mutable;
    }

    static void writeProperties(File directory, Properties properties) throws IOException {
        validateProperties(properties);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        properties.store(bytes, "Pixel 2D Face Unlock");
        if (bytes.size() > MAX_STORE_BYTES) throw new IOException("Store exceeds size limit");
        writeBytes(new File(directory, STORE_FILE), bytes.toByteArray());
    }

    static byte[] readBytes(File file, int maximum) throws IOException {
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximum, 4096));
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() > maximum - count) throw new IOException("File exceeds size limit");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } catch (FileNotFoundException missing) {
            // Permission errors, a directory at this path, or an inaccessible backup must
            // not be confused with first use and overwritten with defaults.
            if (file.exists() || new File(file.getPath() + ".bak").exists()) throw missing;
            return null;
        }
    }

    static void writeText(File file, String value, int maximum) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximum) throw new IOException("Text exceeds size limit");
        writeBytes(file, bytes);
    }

    static String readText(File file, int maximum) throws IOException {
        byte[] bytes = readBytes(file, maximum);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    static void delete(File file) throws IOException {
        new AtomicFile(file).delete();
        if (file.exists() || new File(file.getPath() + ".bak").exists()
                || new File(file.getPath() + ".new").exists()) {
            throw new IOException("Unable to delete stored state");
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(bytes);
            // Propagate fsync failures; AtomicFile.finishWrite alone only logs them.
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            // AtomicFile.finishWrite returns void and only logs rename/close failures.
            // Confirm publication so enrollment and launch grants never claim false success.
            byte[] persisted = readBytes(file, bytes.length);
            if (persisted == null || !java.util.Arrays.equals(bytes, persisted)) {
                throw new IOException("Atomic publication failed");
            }
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Atomic write failed");
        }
    }

    private static void validateProperties(Properties properties) throws IOException {
        if (properties.size() > MAX_PROPERTIES) throw new IOException("Too many properties");
        for (Object keyObject : properties.keySet()) {
            Object valueObject = properties.get(keyObject);
            if (!(keyObject instanceof String) || !(valueObject instanceof String)) {
                throw new IOException("Invalid property type");
            }
            String key = (String) keyObject;
            String value = (String) valueObject;
            if (key.isEmpty() || key.length() > 128 || value.length() > MAX_STORE_BYTES) {
                throw new IOException("Invalid property length");
            }
        }
    }

    private static final class UniqueProperties extends Properties {
        @Override public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) throw new IllegalArgumentException("Duplicate property");
            return super.put(key, value);
        }
    }
}

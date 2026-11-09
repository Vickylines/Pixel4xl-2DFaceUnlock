package com.yusd.pixel2dface;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

/** One interpreter per capture, confined to the serial camera analysis lane. */
final class MobileFaceEmbedder implements AutoCloseable {
    static final String ASSET = "models/mobilefacenet.tflite";
    static final String SHA256 = "d8ba40c0127fb8ca9917e8fddc79bbbda063657bc92a496d34da0bc8a760443b";
    // Only immutable, checksum-verified APK weights are shared. Each capture still owns
    // its interpreter, image buffers and output vectors, and clears them when it closes.
    private static ByteBuffer verifiedModel;
    final ByteBuffer input = ByteBuffer.allocateDirect(112 * 112 * 3 * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    // This pinned graph contains fixed two-image reshapes. Feed two copies instead of
    // resizing its input to one and relying on an invalid internal graph shape.
    private final ByteBuffer batchInput = ByteBuffer.allocateDirect(2 * 112 * 112 * 3 * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    private final float[][] output = new float[2][EmbeddingIdentity.DIMENSIONS];
    private final ByteBuffer modelBuffer;
    private Interpreter interpreter;

    MobileFaceEmbedder(Context context) throws IOException {
        modelBuffer = loadModel(context);
        Interpreter created = new Interpreter(modelBuffer, new Interpreter.Options().setNumThreads(2));
        try {
            created.allocateTensors();
            if (created.getInputTensorCount() != 1 || created.getOutputTensorCount() != 1
                    || created.getInputTensor(0).dataType() != DataType.FLOAT32
                    || created.getOutputTensor(0).dataType() != DataType.FLOAT32
                    || !Arrays.equals(created.getInputTensor(0).shape(), new int[]{2, 112, 112, 3})
                    || !Arrays.equals(created.getOutputTensor(0).shape(), new int[]{2, EmbeddingIdentity.DIMENSIONS})) {
                throw new IOException("Unexpected face model tensors");
            }
            interpreter = created;
        } finally { if (interpreter == null) created.close(); }
    }

    float[] embed() {
        if (interpreter == null) throw new IllegalStateException("Face model is closed");
        input.rewind();
        batchInput.clear();
        batchInput.put(input);
        input.rewind();
        batchInput.put(input);
        batchInput.rewind();
        interpreter.run(batchInput, output);
        return EmbeddingIdentity.normalize(output[0]);
    }
    @Override public void close() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
        input.clear();
        while (input.remaining() >= Float.BYTES) input.putFloat(0f);
        batchInput.clear();
        while (batchInput.remaining() >= Float.BYTES) batchInput.putFloat(0f);
        for (float[] vector : output) Arrays.fill(vector, 0f);
    }
    private static synchronized ByteBuffer loadModel(Context context) throws IOException {
        if (verifiedModel == null) {
            try (AssetFileDescriptor asset = context.getAssets().openFd(ASSET);
                    FileInputStream stream = new FileInputStream(asset.getFileDescriptor())) {
                long length = asset.getDeclaredLength();
                if (length < 1_000_000 || length > 16_000_000) {
                    throw new IOException("Unexpected face model asset size");
                }
                ByteBuffer mapped = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                        asset.getStartOffset(), length);
                if (!hash(mapped).equals(SHA256)) throw new IOException("Unexpected face model asset");
                verifiedModel = mapped.asReadOnlyBuffer();
            }
        }
        return verifiedModel.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
    }

    private static String hash(ByteBuffer bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes.duplicate());
            byte[] hash = digest.digest();
            StringBuilder encoded = new StringBuilder();
            for (byte value : hash) encoded.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

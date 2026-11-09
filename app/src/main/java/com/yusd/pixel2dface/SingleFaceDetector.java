package com.yusd.pixel2dface;

import android.graphics.ImageFormat;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetector;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Joins full-frame counting with contour mode, which only returns the most prominent face. */
final class SingleFaceDetector implements AutoCloseable {
    private final FaceDetector population;
    private final FaceDetector detail;
    private final Executor executor;
    private final BooleanSupplier closing;

    SingleFaceDetector(FaceDetector population, FaceDetector detail,
            Executor executor, BooleanSupplier closing) {
        this.population = population;
        this.detail = detail;
        this.executor = executor;
        this.closing = closing;
    }

    Task<List<Face>> process(InputImage image) {
        if (closing.getAsBoolean()) return Tasks.forResult(List.of());
        Task<List<Face>> counted = processSafely(population, image);
        Task<List<Face>> detailed = processSafely(detail, image);
        // Both readers own the SAME still-open image. Wait for both, including failures:
        // returning early would let CameraX close a plane still used by the other client.
        return Tasks.whenAllComplete(executor, counted, detailed).continueWith(executor, completed -> {
            List<Face> faces = counted.getResult(Exception.class);
            List<Face> contours = detailed.getResult(Exception.class);
            return closing.getAsBoolean() || faces.size() != 1 ? faces : contours;
        });
    }

    private static Task<List<Face>> processSafely(FaceDetector detector, InputImage image) {
        try { return detector.process(image); }
        catch (RuntimeException failure) {
            // A synchronous SDK failure must also join the other already-started reader.
            return Tasks.forException(failure);
        } catch (LinkageError failure) {
            return Tasks.forException(new IllegalStateException("Native detector could not start", failure));
        }
    }

    /** Load both native detectors while the camera opens. These results are never evidence. */
    Task<List<Face>> warmUp() {
        byte[] blank = new byte[64 * 64 * 3 / 2];
        Arrays.fill(blank, 0, 64 * 64, (byte) 16);
        Arrays.fill(blank, 64 * 64, blank.length, (byte) 128);
        InputImage image = InputImage.fromByteArray(blank, 64, 64, 0, ImageFormat.NV21);
        return process(image);
    }

    @Override public void close() {
        try { population.close(); }
        finally { detail.close(); }
    }
}

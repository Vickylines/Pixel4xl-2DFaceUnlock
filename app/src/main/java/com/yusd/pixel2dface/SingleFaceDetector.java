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

/** Counts faces before contour mode, which only returns the most prominent face. */
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
        // Both stages inspect the same still-open CameraX image. A failed stage propagates
        // to the existing frame-rejection path; it cannot fall back to single-face evidence.
        return population.process(image).onSuccessTask(executor, faces -> {
            if (closing.getAsBoolean() || faces.size() != 1) return Tasks.forResult(faces);
            return detail.process(image);
        });
    }

    /** Load both native detectors while the camera opens. These results are never evidence. */
    Task<List<Face>> warmUp() {
        byte[] blank = new byte[64 * 64 * 3 / 2];
        Arrays.fill(blank, 0, 64 * 64, (byte) 16);
        Arrays.fill(blank, 64 * 64, blank.length, (byte) 128);
        InputImage image = InputImage.fromByteArray(blank, 64, 64, 0, ImageFormat.NV21);
        return population.process(image).onSuccessTask(executor, ignored ->
                closing.getAsBoolean() ? Tasks.forResult(List.of()) : detail.process(image));
    }

    @Override public void close() {
        try { population.close(); }
        finally { detail.close(); }
    }
}

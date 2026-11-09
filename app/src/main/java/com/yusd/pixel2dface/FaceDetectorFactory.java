package com.yusd.pixel2dface;

import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Each capture owns its counting and contour clients, including their native resources. */
final class FaceDetectorFactory {
    private FaceDetectorFactory() {
    }

    static SingleFaceDetector create(Executor executor, BooleanSupplier closing) {
        FaceDetector population = FaceDetection.getClient(populationOptions());
        try {
            return new SingleFaceDetector(population, FaceDetection.getClient(detailOptions()),
                    executor, closing);
        } catch (RuntimeException | LinkageError failure) {
            population.close();
            throw failure;
        }
    }

    static FaceDetectorOptions populationOptions() {
        return new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.10f)
                .build();
    }

    static FaceDetectorOptions detailOptions() {
        return new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.20f)
                .build();
    }
}

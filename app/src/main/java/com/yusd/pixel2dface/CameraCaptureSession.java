package com.yusd.pixel2dface;

import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;

import androidx.activity.ComponentActivity;
import androidx.annotation.MainThread;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ExperimentalCameraInfo;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the camera, with one detection and one ordered verification allowed in flight. */
@androidx.annotation.OptIn(markerClass = {
        ExperimentalGetImage.class, ExperimentalCamera2Interop.class, ExperimentalCameraInfo.class
})
final class CameraCaptureSession {
    interface Listener {
        void onReady();
        void onFrame(FaceFrameExtractor.Result frame, long capturedAtMs, long detectedAtMs);
        void onRejectedFrame(String reason);
        void onCameraError(String message);
        default void onLowLight() { }
    }

    private static final String TAG = "Pixel2DFace";
    private static final long MAX_ANALYSIS_AGE_MS = RecognitionEngine.MAX_FRAME_AGE_MS;
    private final ComponentActivity owner;
    private final Listener listener;
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final ExecutorService verifier = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureSessionGate gate = new CaptureSessionGate();
    private final DetectionOrientation detectionOrientation = new DetectionOrientation();
    private final CaptureLighting lighting = new CaptureLighting();
    private final CaptureRotation rotation;
    private final OrientationEventListener orientationListener;
    private volatile long rotationGeneration;
    private long lastFrameRotationGeneration = -1;
    private int lastFrameRotation = -1;
    // Only the analyzer uses the frame, detector and reusable sampling workspace.
    // The verifier borrows the sampled data until its future completes.
    private FrameTimestampNormalizer timestamps;
    private SingleFaceDetector detector;
    private FaceFrameExtractor extractor;
    private CompletableFuture<Void> verification;
    private MobileFaceEmbedder embedder;
    // CameraX binding and unbinding are confined to the main thread.
    private ProcessCameraProvider provider;
    private Camera camera;
    private ImageAnalysis analysis;
    private Preview preview;
    private long requestedAt;
    private boolean firstImageLogged;
    private long firstImageAt;
    private long lastImageAt;
    private int detectedFrames;
    private long totalDetectionMs;
    private long totalEvaluationMs;

    CameraCaptureSession(ComponentActivity owner, Listener listener) {
        this.owner = owner;
        this.listener = listener;
        rotation = new CaptureRotation(owner.getWindowManager().getDefaultDisplay().getRotation());
        orientationListener = new OrientationEventListener(owner, SensorManager.SENSOR_DELAY_UI) {
            @Override public void onOrientationChanged(int degrees) {
                if (gate.isClosing() || !rotation.update(degrees)) return;
                rotationGeneration++;
                if (analysis != null) analysis.setTargetRotation(rotation.surfaceRotation());
                Log.i(TAG, "Camera orientation targetDegrees="
                        + rotation.surfaceRotation() * 90);
            }
        };
    }

    @MainThread
    void loadConfiguration(Runnable action) {
        analyzer.execute(() -> { if (!gate.isClosing()) action.run(); });
    }

    /** Called only after configuration and, on keyguard, launch authorization. */
    @MainThread
    void start(PreviewView previewView, boolean neural) {
        if (!gate.acquireFrame()) return;
        requestedAt = SystemClock.elapsedRealtime();
        verification = CompletableFuture.runAsync(() -> initializeVerifier(neural), verifier);
        analyzer.execute(() -> initializeDetector(neural));
        try {
            bind(previewView);
        } catch (RuntimeException failure) {
            failCamera("Front camera initialization failed", "无法打开相机", failure);
        }
    }

    private void initializeVerifier(boolean neural) {
        if (gate.isClosing()) return;
        try {
            if (neural) embedder = new MobileFaceEmbedder(owner);
            listener.onReady();
        } catch (IOException | RuntimeException | LinkageError failure) {
            failCamera("Face model initialization failed", "暂时无法识别", failure);
        }
    }

    private void initializeDetector(boolean neural) {
        // The placeholder owns the reader slot until both native clients finish.
        if (gate.isClosing()) {
            completeRead();
            return;
        }
        try {
            extractor = new FaceFrameExtractor(neural);
            detector = FaceDetectorFactory.create(analyzer, gate::isClosing);
            long warmUpAt = SystemClock.elapsedRealtime();
            detector.warmUp().addOnCompleteListener(analyzer, task -> {
                try {
                    if (!task.isSuccessful()) {
                        failCamera("Detector warm-up failed", "暂时无法识别", task.getException());
                    }
                    Log.i(TAG, "Detector warm-up elapsedMs="
                            + (SystemClock.elapsedRealtime() - warmUpAt));
                } finally {
                    completeRead();
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            try { failCamera("Detector initialization failed", "暂时无法识别", failure); }
            finally { completeRead(); }
        }
    }

    @MainThread
    private void bind(PreviewView previewView) {
        if (gate.isClosing() || owner.isFinishing() || owner.isDestroyed()) return;
        orientationListener.enable();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(owner);
        future.addListener(() -> {
            if (gate.isClosing() || owner.isFinishing() || owner.isDestroyed()) return;
            try {
                provider = future.get();
                CameraSelector front = CameraSelector.DEFAULT_FRONT_CAMERA;
                Integer clockSource = Camera2CameraInfo.from(provider.getCameraInfo(front))
                        .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
                boolean realtime = Integer.valueOf(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                        .equals(clockSource);
                timestamps = new FrameTimestampNormalizer(realtime);
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(rotation.surfaceRotation())
                        .setTargetResolution(new Size(480, 360)).build();
                analysis.setAnalyzer(analyzer, this::analyze);
                if (previewView == null) {
                    camera = provider.bindToLifecycle(owner, front, analysis);
                } else {
                    preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    camera = provider.bindToLifecycle(owner, front, analysis, preview);
                }
                Log.d(TAG, "Camera timestamp source=" + (realtime ? "realtime" : "estimated"));
            } catch (Exception failure) {
                failCamera("Front camera unavailable", "无法打开相机", failure);
            }
        }, owner.getMainExecutor());
    }

    private void analyze(ImageProxy frame) {
        if (!gate.acquireFrame()) {
            frame.close();
            return;
        }
        long startedAt = SystemClock.elapsedRealtime();
        if (!firstImageLogged) {
            firstImageLogged = true;
            Log.i(TAG, "Camera first image startupMs=" + (startedAt - requestedAt)
                    + " resolution=" + frame.getWidth() + "x" + frame.getHeight()
                    + " rotationDegrees=" + frame.getImageInfo().getRotationDegrees());
        }
        boolean detecting = false;
        try {
            long capturedAt = timestamps.normalize(frame.getImageInfo().getTimestamp(), startedAt);
            android.media.Image image = frame.getImage();
            if (image == null || capturedAt < 0) {
                reject("metadata");
                return;
            }
            if (startedAt - capturedAt > MAX_ANALYSIS_AGE_MS) {
                reject("frame_age");
                return;
            }
            ImageProxy.PlaneProxy luma = frame.getPlanes()[0];
            if (lighting.needsLight(
                    luma.getBuffer(), frame.getWidth(), frame.getHeight(), luma.getRowStride(),
                    luma.getPixelStride(), capturedAt)) {
                main.post(this::assistLowLight);
            }
            if (gate.isClosing()) return;
            long frameRotationGeneration = rotationGeneration;
            int rotation = detectionOrientation.rotationFor(frame.getImageInfo().getRotationDegrees());
            // A changed basis clears earlier evidence, before this frame is evaluated.
            boolean rotated = lastFrameRotation != -1 && (rotation != lastFrameRotation
                    || frameRotationGeneration != lastFrameRotationGeneration);
            lastFrameRotation = rotation;
            lastFrameRotationGeneration = frameRotationGeneration;
            InputImage input = InputImage.fromMediaImage(image, rotation);
            detector.process(input).addOnCompleteListener(analyzer, result -> onDetection(
                    result, frame, rotation, rotated, frameRotationGeneration, startedAt, capturedAt));
            detecting = true;
        } catch (RuntimeException failure) {
            Log.w(TAG, "Detector rejected a frame", failure);
            reject("detector_error");
        } finally {
            if (!detecting) complete(frame);
        }
    }

    private void onDetection(Task<List<Face>> result, ImageProxy frame, int rotation,
            boolean rotated, long generation, long startedAt, long capturedAt) {
        try {
            if (gate.isClosing()) return;
            if (!result.isSuccessful()) {
                reject("detector_error");
                return;
            }
            long detectedAt = SystemClock.elapsedRealtime();
            if (detectedFrames == 0) firstImageAt = startedAt;
            lastImageAt = startedAt;
            detectedFrames++;
            totalDetectionMs += detectedAt - startedAt;
            if (detectedAt - startedAt > MAX_ANALYSIS_AGE_MS) {
                reject("analysis_age");
                return;
            }
            // The detector has already run in parallel with the previous embedding. Wait
            // for that verifier before reusing its sample buffer or issuing a new verdict.
            verification.join();
            if (gate.isClosing()) return;
            String rejection = invalidFrameReason(generation, capturedAt);
            if (rejection != null) {
                verify(() -> listener.onRejectedFrame(rejection));
                return;
            }
            List<Face> faces = result.getResult();
            detectionOrientation.observe(faces.size(), faces.size() == 1
                    ? faces.get(0).getHeadEulerAngleZ() : 0f);
            FaceFrameExtractor.Result sample = extractor.extract(frame, rotation, faces);
            verify(() -> evaluate(sample, rotated, generation, capturedAt, detectedAt));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Frame sampling failed", failure);
            verify(() -> listener.onRejectedFrame("evaluation_error"));
        } finally {
            complete(frame);
        }
    }

    private void evaluate(FaceFrameExtractor.Result frame, boolean rotated,
            long generation, long capturedAt, long detectedAt) {
        try {
            if (rejectInvalidFrame(generation, capturedAt)) return;
            if (rotated) listener.onRejectedFrame("rotation_changed");
            if (frame.reason == FrameQualityPolicy.Reason.NONE && embedder != null) {
                frame = frame.withTexture(embedder.embed(extractor.neuralInput));
            }
            if (!gate.isClosing() && !rejectInvalidFrame(generation, capturedAt)) {
                listener.onFrame(frame, capturedAt, detectedAt);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Frame evaluation failed", failure);
            if (!gate.isClosing()) listener.onRejectedFrame("evaluation_error");
        } catch (LinkageError failure) {
            failCamera("Face model inference failed", "暂时无法识别", failure);
        } finally {
            totalEvaluationMs += SystemClock.elapsedRealtime() - detectedAt;
        }
    }

    private boolean rejectInvalidFrame(long generation, long capturedAt) {
        String reason = invalidFrameReason(generation, capturedAt);
        if (reason == null) return false;
        listener.onRejectedFrame(reason);
        return true;
    }

    private String invalidFrameReason(long generation, long capturedAt) {
        if (generation != rotationGeneration) return "rotation_changed";
        return SystemClock.elapsedRealtime() - capturedAt > MAX_ANALYSIS_AGE_MS ? "frame_age" : null;
    }

    private void reject(String reason) {
        // Rejected frames clear evidence in the same order as evaluated frames.
        verification.join();
        verify(() -> listener.onRejectedFrame(reason));
    }

    private void verify(Runnable action) {
        if (!gate.isClosing()) {
            verification = CompletableFuture.runAsync(() -> {
                if (!gate.isClosing()) action.run();
            }, verifier);
        }
    }

    private void complete(ImageProxy frame) {
        try { frame.close(); }
        finally { completeRead(); }
    }

    private void completeRead() {
        gate.completeFrame();
        releaseIfIdle();
    }

    private void failCamera(String diagnostic, String message, Throwable failure) {
        if (!gate.requestClose()) return;
        Log.w(TAG, diagnostic, failure);
        listener.onCameraError(message);
    }

    private void assistLowLight() {
        if (gate.isClosing() || camera == null) return;
        listener.onLowLight();
        ExposureState exposure = camera.getCameraInfo().getExposureState();
        if (!exposure.isExposureCompensationSupported()) return;
        int target = Math.min(exposure.getExposureCompensationRange().getUpper(),
                Math.round(.67f / exposure.getExposureCompensationStep().floatValue()));
        ListenableFuture<Integer> applied = camera.getCameraControl().setExposureCompensationIndex(target);
        applied.addListener(() -> {
            try {
                Log.i(TAG, "Low-light exposure applied index=" + applied.get());
            } catch (Exception failure) {
                if (!gate.isClosing()) Log.w(TAG, "Low-light exposure unavailable", failure);
            }
        }, owner.getMainExecutor());
    }

    /** Idempotent. The detector stays alive until its outstanding task closes its frame. */
    @MainThread
    void close() {
        gate.requestClose();
        orientationListener.disable();
        camera = null;
        if (analysis != null) {
            analysis.clearAnalyzer();
            provider.unbind(analysis);
            analysis = null;
        }
        if (preview != null) {
            provider.unbind(preview);
            preview = null;
        }
        releaseIfIdle();
    }

    private void releaseIfIdle() {
        if (!gate.claimResourceRelease()) return;
        // Closing the native detector can block; queue it on the analysis lane even when
        // Activity failure/stop calls close() from the main thread with no frame in flight.
        analyzer.execute(this::releaseDetector);
        analyzer.shutdown();
    }

    private void releaseDetector() {
        try {
            if (detector != null) detector.close();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Detector close failed", failure);
        } finally {
            detector = null;
            verification = null;
            verifier.execute(this::releaseVerifier);
            verifier.shutdown();
        }
    }

    private void releaseVerifier() {
        if (detectedFrames > 0) {
            Log.i(TAG, "Camera analysis frames=" + detectedFrames
                    + " detectionMeanMs=" + totalDetectionMs / detectedFrames
                    + " evaluationMeanMs=" + totalEvaluationMs / detectedFrames
                    + " frameIntervalMeanMs=" + (detectedFrames < 2 ? 0
                            : (lastImageAt - firstImageAt) / (detectedFrames - 1)));
        }
        try {
            if (embedder != null) embedder.close();
        } finally {
            embedder = null;
            if (extractor != null) extractor.clear();
            extractor = null;
        }
    }
}

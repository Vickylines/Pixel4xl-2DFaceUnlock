package com.yusd.pixel2dface;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetector;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Owns one camera binding, one detector and one serial analysis lane per Activity. */
@androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
final class CameraCaptureSession {
    interface Listener {
        void onEngineReady(long elapsedMs);
        void onFrame(ImageProxy image, int rotation, List<Face> faces, long capturedAtMs);
        void onRejectedFrame(String reason);
        void onCameraError(String message);
    }

    private static final long MAX_ANALYSIS_AGE_MS = 750;
    private final ComponentActivity owner;
    private final Listener listener;
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CaptureSessionGate gate = new CaptureSessionGate();
    private final FrameTimestampNormalizer timestamps = new FrameTimestampNormalizer();
    private final Object detectorLock = new Object();
    private FaceDetector detector;
    // CameraX binding and unbinding are confined to the main thread.
    private ProcessCameraProvider provider;
    private ImageAnalysis analysis;
    private Preview preview;
    private long requestedAt;
    private boolean firstImageLogged;

    CameraCaptureSession(ComponentActivity owner, Listener listener) {
        this.owner = owner;
        this.listener = listener;
    }

    boolean execute(Runnable action) {
        if (gate.isClosing()) return false;
        try {
            analyzer.execute(() -> { if (!gate.isClosing()) action.run(); });
            return true;
        } catch (RejectedExecutionException closed) {
            return false;
        }
    }

    /** Called on the analysis lane after configuration has been loaded. */
    void start(PreviewView previewView) {
        requestedAt = SystemClock.elapsedRealtime();
        FaceDetector created = FaceDetectorFactory.create();
        synchronized (detectorLock) {
            if (gate.isClosing()) {
                created.close();
                return;
            }
            detector = created;
        }
        listener.onEngineReady(SystemClock.elapsedRealtime());
        main.post(() -> bind(previewView));
    }

    private void bind(PreviewView previewView) {
        if (gate.isClosing() || owner.isFinishing() || owner.isDestroyed()) return;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(owner);
        future.addListener(() -> {
            if (gate.isClosing() || owner.isFinishing() || owner.isDestroyed()) return;
            try {
                provider = future.get();
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new Size(480, 360)).build();
                analysis.setAnalyzer(analyzer, this::analyze);
                if (previewView == null) {
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                } else {
                    preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA,
                            analysis, preview);
                }
            } catch (Exception failure) {
                listener.onCameraError("无法启动前置摄像头："
                        + failure.getClass().getSimpleName());
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
            Log.i("Pixel2DFace", "Camera first image startupMs=" + (startedAt - requestedAt)
                    + " resolution=" + frame.getWidth() + "x" + frame.getHeight());
        }
        try {
            long capturedAt = timestamps.normalize(frame.getImageInfo().getTimestamp(), startedAt);
            int rotation = frame.getImageInfo().getRotationDegrees();
            if (frame.getImage() == null || capturedAt < 0) {
                listener.onRejectedFrame("metadata");
                complete(frame);
                return;
            }
            InputImage input = InputImage.fromMediaImage(frame.getImage(), rotation);
            FaceDetector current;
            synchronized (detectorLock) { current = detector; }
            if (current == null || gate.isClosing()) {
                complete(frame);
                return;
            }
            current.process(input)
                    .addOnSuccessListener(analyzer, faces -> {
                        if (gate.isClosing()) return;
                        long evaluatedAt = SystemClock.elapsedRealtime();
                        String rejected = evaluatedAt - startedAt > MAX_ANALYSIS_AGE_MS
                                ? "analysis_age" : evaluatedAt < capturedAt ? "future_timestamp"
                                : evaluatedAt - capturedAt > MAX_ANALYSIS_AGE_MS ? "frame_age" : null;
                        if (rejected != null) {
                            listener.onRejectedFrame(rejected);
                            return;
                        }
                        try {
                            listener.onFrame(frame, rotation, faces, capturedAt);
                        } catch (RuntimeException failure) {
                            Log.w("Pixel2DFace", "Frame evaluation failed", failure);
                            listener.onRejectedFrame("evaluation_error");
                        }
                    })
                    .addOnFailureListener(analyzer, failure -> {
                        if (!gate.isClosing()) listener.onRejectedFrame("detector_error");
                    })
                    .addOnCompleteListener(analyzer, task -> complete(frame));
        } catch (RuntimeException failure) {
            Log.w("Pixel2DFace", "Detector rejected a frame", failure);
            if (!gate.isClosing()) listener.onRejectedFrame("detector_error");
            complete(frame);
        }
    }

    private void complete(ImageProxy frame) {
        try { frame.close(); }
        finally {
            gate.completeFrame();
            releaseIfIdle();
        }
    }

    /** Idempotent. The detector stays alive until its outstanding task closes its frame. */
    void close() {
        gate.requestClose();
        if (Looper.myLooper() == Looper.getMainLooper()) unbind();
        else main.post(this::unbind);
        releaseIfIdle();
    }

    private void unbind() {
        if (analysis != null) {
            analysis.clearAnalyzer();
            if (provider != null) provider.unbind(analysis);
            analysis = null;
        }
        if (preview != null) {
            if (provider != null) provider.unbind(preview);
            preview = null;
        }
    }

    private void releaseIfIdle() {
        if (!gate.claimResourceRelease()) return;
        // Closing the native detector can block; queue it on the analysis lane even when
        // Activity failure/stop calls close() from the main thread with no frame in flight.
        analyzer.execute(this::releaseDetector);
        analyzer.shutdown();
    }

    private void releaseDetector() {
        FaceDetector released;
        synchronized (detectorLock) {
            released = detector;
            detector = null;
        }
        try {
            if (released != null) released.close();
        } catch (RuntimeException failure) {
            Log.w("Pixel2DFace", "Detector close failed", failure);
        }
    }
}

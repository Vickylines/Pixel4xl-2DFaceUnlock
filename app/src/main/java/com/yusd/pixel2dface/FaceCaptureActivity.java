package com.yusd.pixel2dface;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
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
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
public class FaceCaptureActivity extends ComponentActivity {
    private static final String TAG = "Pixel2DFace";
    private static final int REQUEST_CAMERA = 200;
    private static final long SESSION_TIMEOUT_MS = 12_000L;
    private static final int ENROLLMENT_SAMPLES = 10;
    private static final int REQUIRED_MATCHES = RecognitionStabilizer.REQUIRED_MATCHES;
    private static final float MAX_ABS_YAW_DEGREES = 28f;
    private static final float MAX_ABS_PITCH_DEGREES = 22f;
    private static final float MAX_ABS_ROLL_DEGREES = 25f;
    private static final float MIN_FACE_WIDTH_RATIO = 0.18f;
    private static final float MAX_CENTER_OFFSET_X_RATIO = 0.34f;
    private static final float MAX_CENTER_OFFSET_Y_RATIO = 0.36f;
    private static final float MIN_BRIGHTNESS = 12f;
    private static final float MAX_BRIGHTNESS = 248f;
    private static final float MIN_CONTRAST = 6f;
    private static final float MIN_SHARPNESS = 1.5f;

    private final ExecutorService analyzerExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final AtomicBoolean hostFinishedBroadcastSent = new AtomicBoolean(false);
    private final AtomicBoolean resourceReleaseRequested = new AtomicBoolean(false);
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LbpDescriptor.Workspace descriptorWorkspace = new LbpDescriptor.Workspace();
    private final Object detectorLock = new Object();

    private final Runnable keyguardExitWatcher = new Runnable() {
        @Override
        public void run() {
            if (!Constants.MODE_UNLOCK.equals(mode) || isFinishing() || isDestroyed()) {
                return;
            }
            KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
            // isKeyguardLocked() becomes false while a showWhenLocked Activity merely
            // occludes keyguard. isDeviceLocked() only becomes false after the user really
            // dismisses the lock screen, which prevents the premature task close/white flash.
            if (keyguardManager == null || !keyguardManager.isDeviceLocked()) {
                finishHostWithoutTransition();
                return;
            }
            mainHandler.postDelayed(this, 80L);
        }
    };

    private PreviewView previewView;
    private TextView instructionView;
    private TextView detailView;
    private ProcessCameraProvider cameraProvider;
    private FaceDetector detector;
    private String mode;
    private String sessionToken;
    private long sessionStartedAt;
    private long firstFrameAt;
    private long lastEnrollmentCaptureAt;
    private long lastDiagnosticAt;
    private int processedFrameCount;
    private float bestScore = Float.MAX_VALUE;

    private final List<float[]> enrollmentSamples = new ArrayList<>();
    private final List<float[]> enrollmentGeometrySamples = new ArrayList<>();

    private IdentityModel identityModel;
    private float recognitionThreshold = TemplateStore.DEFAULT_THRESHOLD;
    private final RecognitionStabilizer recognitionStabilizer = new RecognitionStabilizer();
    private int consecutiveMatches;
    private boolean eyesCurrentlyOpen;
    private Float lastLeftEyeProbability;
    private Float lastRightEyeProbability;
    private float lastLeftEyeContourRatio;
    private float lastRightEyeContourRatio;
    private int consecutiveOpenEyeFrames;
    private float lastFrontScore = Float.MAX_VALUE;
    private float lastGeometryScore = Float.MAX_VALUE;
    private float lastGeometryPeak = Float.MAX_VALUE;
    private int lastConsistentCells;
    private int lastConsistentCoreCells;
    private float lastBrightness;
    private float lastContrast;
    private float lastSharpness;
    private float lastYaw;
    private float lastPitch;
    private float lastRoll;
    private float lastFaceWidthRatio;
    private float lastCenterOffsetX;
    private float lastCenterOffsetY;
    private BroadcastReceiver screenOffReceiver;
    private int hostReadyAttempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(Constants.EXTRA_MODE);
        if (mode == null) {
            mode = Constants.MODE_TEST;
        }
        boolean unlockMode = Constants.MODE_UNLOCK.equals(mode);
        if (unlockMode != isExternalUnlockHost()) {
            finish();
            return;
        }
        sessionToken = getIntent().getStringExtra(Constants.EXTRA_SESSION_TOKEN);
        if (unlockMode && !TemplateStore.consumeAuthorizedUnlockSession(this, sessionToken)) {
            finish();
            return;
        }
        if (Constants.MODE_UNLOCK.equals(mode)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureWindow();
        if (Constants.MODE_UNLOCK.equals(mode)) {
            registerScreenOffReceiver();
        }
        setContentView(buildContent());
        if (Constants.MODE_UNLOCK.equals(mode)) {
            configureSilentUnlockWindow();
            getWindow().getDecorView().post(this::notifySystemUiHostReady);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeEngineAndStartCamera();
        } else if (Constants.MODE_UNLOCK.equals(mode)) {
            finishFailure(false, "未授予相机权限");
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    /** Only the exported SystemUI host subclass may enter silent lock-screen mode. */
    protected boolean isExternalUnlockHost() {
        return false;
    }

    private View buildContent() {
        if (Constants.MODE_UNLOCK.equals(mode)) {
            return buildUnlockContent();
        }
        return buildCameraContent();
    }

    private View buildUnlockContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        return root;
    }

    private View buildCameraContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new FaceGuideView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(dp(24), dp(36), dp(24), dp(16));
        top.setBackgroundColor(Color.argb(145, 0, 0, 0));
        instructionView = new TextView(this);
        instructionView.setTextColor(Color.WHITE);
        instructionView.setTextSize(22);
        instructionView.setGravity(Gravity.CENTER);
        instructionView.setText(initialInstruction());
        top.addView(instructionView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        detailView = new TextView(this);
        detailView.setTextColor(Color.rgb(215, 225, 235));
        detailView.setTextSize(14);
        detailView.setGravity(Gravity.CENTER);
        detailView.setPadding(0, dp(8), 0, 0);
        detailView.setText("请将脸保持在取景框内");
        top.addView(detailView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(top, topParams);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setTextSize(15);
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> finishFailure(false, "已取消"));
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                dp(148), dp(54), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelParams.bottomMargin = dp(36);
        root.addView(cancel, cancelParams);
        return root;
    }

    private void configureWindow() {
        if (Constants.MODE_UNLOCK.equals(mode)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }

    private void configureSilentUnlockWindow() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.width = 1;
        attributes.height = 1;
        attributes.gravity = Gravity.TOP | Gravity.START;
        attributes.x = 0;
        attributes.y = 0;
        attributes.dimAmount = 0f;
        // A transparent background still has WindowManager alpha=1 and Android 12+ may block
        // cross-UID touches behind it. Alpha 0 is the platform-defined safe pass-through case.
        attributes.alpha = 0f;
        getWindow().setAttributes(attributes);
        getWindow().setLayout(1, 1);
    }

    private void notifySystemUiHostReady() {
        if (!Constants.MODE_UNLOCK.equals(mode) || finished.get() || sessionToken == null) {
            return;
        }
        // LayoutParams.token is the ActivityRecord token expected by ActivityClient. The
        // decor view's window token identifies IWindow instead and cannot disable the sink.
        android.os.IBinder activityToken = getWindow().getAttributes().token;
        if (activityToken == null) {
            if (hostReadyAttempts++ < 12) {
                mainHandler.postDelayed(this::notifySystemUiHostReady, 16L);
            }
            return;
        }
        Bundle extras = new Bundle();
        extras.putString(Constants.EXTRA_SESSION_TOKEN, sessionToken);
        extras.putBinder(Constants.EXTRA_ACTIVITY_TOKEN, activityToken);
        sendBroadcast(new Intent(Constants.ACTION_CAMERA_HOST_READY)
                .setPackage(Constants.SYSTEM_UI_PACKAGE)
                .putExtras(extras));
    }

    private String initialInstruction() {
        if (Constants.MODE_ENROLL.equals(mode)) {
            return "录入人脸：请正视镜头";
        }
        if (Constants.MODE_UNLOCK.equals(mode)) {
            return "正在识别人脸";
        }
        return "测试人脸识别";
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeEngineAndStartCamera();
        } else {
            finishFailure(false, "需要相机权限才能识别人脸");
        }
    }

    private void initializeEngineAndStartCamera() {
        analyzerExecutor.execute(() -> {
            try {
                if (!Constants.MODE_ENROLL.equals(mode)) {
                    identityModel = TemplateStore.loadIdentityModel(this);
                    recognitionThreshold = TemplateStore.getRecognitionThreshold(this);
                    if (identityModel == null) {
                        finishFailure(false, "尚未录入人脸");
                        return;
                    }
                }
                FaceDetector createdDetector = FaceDetectorFactory.create();
                synchronized (detectorLock) {
                    if (resourceReleaseRequested.get() || resourcesReleased.get()) {
                        createdDetector.close();
                        return;
                    }
                    detector = createdDetector;
                }
                mainHandler.post(() -> {
                    if (!finished.get() && !isFinishing() && !isDestroyed()) {
                        startCamera();
                    }
                });
            } catch (Throwable error) {
                finishFailure(false, "人脸引擎初始化失败");
            }
        });
    }

    private void startCamera() {
        sessionStartedAt = SystemClock.elapsedRealtime();
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new android.util.Size(480, 360))
                        .build();
                analysis.setAnalyzer(analyzerExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                if (Constants.MODE_UNLOCK.equals(mode)) {
                    // Analysis-only binding keeps the front camera feed completely hidden.
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                            analysis);
                } else {
                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview, analysis);
                }
                if (!Constants.MODE_ENROLL.equals(mode)) {
                    mainHandler.postDelayed(this::handleTimeout, SESSION_TIMEOUT_MS);
                }
            } catch (Exception error) {
                finishFailure(false, "无法启动前置摄像头：" + error.getClass().getSimpleName());
            }
        }, getMainExecutor());
    }

    private void analyzeFrame(ImageProxy image) {
        if (finished.get() || !processing.compareAndSet(false, true)) {
            image.close();
            return;
        }
        android.media.Image mediaImage = image.getImage();
        if (mediaImage == null) {
            completeFrame(image);
            return;
        }
        int rotation = image.getImageInfo().getRotationDegrees();
        InputImage inputImage;
        try {
            inputImage = InputImage.fromMediaImage(mediaImage, rotation);
        } catch (Exception error) {
            completeFrame(image);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (firstFrameAt == 0L) {
            firstFrameAt = now;
        }
        processedFrameCount++;
        detector.process(inputImage)
                .addOnSuccessListener(analyzerExecutor, faces -> {
                    try {
                        processFaces(image, rotation, faces);
                    } catch (Throwable error) {
                        android.util.Log.e(TAG, "Unable to analyze camera frame", error);
                        resetFaceTracking();
                        updateDetail("人脸检测暂时不可用");
                    }
                })
                .addOnFailureListener(analyzerExecutor, error -> {
                    resetFaceTracking();
                    updateDetail("人脸检测暂时不可用");
                })
                .addOnCompleteListener(analyzerExecutor, task -> completeFrame(image));
    }

    private void completeFrame(ImageProxy image) {
        image.close();
        processing.set(false);
        if (resourceReleaseRequested.get()) {
            releaseAnalyzerResources();
        }
    }

    private void processFaces(ImageProxy frame, int rotationDegrees, List<Face> faces) {
        if (finished.get()) {
            return;
        }
        if (faces.size() != 1) {
            rejectRecognitionFrame();
            updateDetail(faces.isEmpty() ? "未检测到人脸" : "画面中只能有一张人脸");
            return;
        }

        int normalizedRotation = ((rotationDegrees % 360) + 360) % 360;
        int uprightWidth = normalizedRotation == 90 || normalizedRotation == 270
                ? frame.getHeight() : frame.getWidth();
        int uprightHeight = normalizedRotation == 90 || normalizedRotation == 270
                ? frame.getWidth() : frame.getHeight();
        Face face = faces.get(0);
        Rect detectedBox = face.getBoundingBox();
        float widthRatio = detectedBox.width() / (float) Math.max(1, uprightWidth);
        lastFaceWidthRatio = widthRatio;
        if (widthRatio < MIN_FACE_WIDTH_RATIO) {
            rejectRecognitionFrame();
            updateDetail("请靠近一点");
            return;
        }
        float centerXOffset = Math.abs(detectedBox.exactCenterX() - uprightWidth * 0.5f)
                / Math.max(1f, uprightWidth);
        float centerYOffset = Math.abs(detectedBox.exactCenterY() - uprightHeight * 0.5f)
                / Math.max(1f, uprightHeight);
        lastCenterOffsetX = centerXOffset;
        lastCenterOffsetY = centerYOffset;
        if (centerXOffset > MAX_CENTER_OFFSET_X_RATIO
                || centerYOffset > MAX_CENTER_OFFSET_Y_RATIO) {
            rejectRecognitionFrame();
            updateDetail("请将脸移到画面中央");
            return;
        }

        updateEyeState(face);
        if (!eyesCurrentlyOpen) {
            rejectRecognitionFrame();
            updateDetail("请自然睁眼并看向屏幕");
            logDiagnostic(Float.MAX_VALUE, Float.MAX_VALUE, false, "eyes");
            return;
        }
        if (!isWithinHardPose(face)) {
            rejectRecognitionFrame();
            updateDetail("请自然正视屏幕");
            logDiagnostic(Float.MAX_VALUE, Float.MAX_VALUE, false, "pose");
            return;
        }

        Rect box = paddedAndClamped(detectedBox, uprightWidth, uprightHeight);
        if (box.width() < 100 || box.height() < 100) {
            rejectRecognitionFrame();
            updateDetail("请靠近一点");
            return;
        }
        PointF leftEye = landmarkPosition(face, FaceLandmark.LEFT_EYE);
        PointF rightEye = landmarkPosition(face, FaceLandmark.RIGHT_EYE);
        PointF nose = landmarkPosition(face, FaceLandmark.NOSE_BASE);
        PointF mouthLeft = landmarkPosition(face, FaceLandmark.MOUTH_LEFT);
        PointF mouthRight = landmarkPosition(face, FaceLandmark.MOUTH_RIGHT);
        PointF mouthBottom = landmarkPosition(face, FaceLandmark.MOUTH_BOTTOM);
        if (leftEye == null || rightEye == null || nose == null || mouthLeft == null
                || mouthRight == null || mouthBottom == null) {
            rejectRecognitionFrame();
            updateDetail("请让完整面部正对屏幕");
            return;
        }
        float[] geometry = FaceGeometry.create(detectedBox.width(), detectedBox.height(),
                leftEye.x, leftEye.y, rightEye.x, rightEye.y, nose.x, nose.y,
                mouthLeft.x, mouthLeft.y, mouthRight.x, mouthRight.y,
                mouthBottom.x, mouthBottom.y);
        if (geometry == null) {
            rejectRecognitionFrame();
            updateDetail("请自然正视屏幕");
            return;
        }

        float[] descriptor;
        try {
            descriptor = descriptorWorkspace.compute(frame, rotationDegrees, box,
                    leftEye, rightEye);
        } catch (RuntimeException error) {
            rejectRecognitionFrame();
            android.util.Log.w(TAG, "Unable to extract face descriptor", error);
            return;
        }
        lastBrightness = descriptorWorkspace.getBrightness();
        lastContrast = descriptorWorkspace.getContrast();
        lastSharpness = descriptorWorkspace.getSharpness();
        String qualityProblem = qualityProblem();
        if (qualityProblem != null) {
            rejectRecognitionFrame();
            updateDetail(qualityProblem);
            logDiagnostic(Float.MAX_VALUE, Float.MAX_VALUE, false, "quality");
            return;
        }

        if (Constants.MODE_ENROLL.equals(mode)) {
            // The workspace is reused on the next frame; enrollment samples must own their data.
            processEnrollment(descriptor.clone(), geometry);
        } else {
            processRecognition(descriptor, geometry);
        }
    }

    private void processEnrollment(float[] descriptor, float[] geometry) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastEnrollmentCaptureAt < 280L) {
            return;
        }
        if (Math.abs(lastYaw) > 18f || Math.abs(lastPitch) > 16f
                || Math.abs(lastRoll) > 18f) {
            updateInstruction("录入人脸：请自然正视镜头");
            return;
        }
        enrollmentSamples.add(descriptor);
        enrollmentGeometrySamples.add(geometry);
        lastEnrollmentCaptureAt = now;
        int count = enrollmentSamples.size();
        updateInstruction("录入人脸：自然看向屏幕即可");
        updateDetail("正在自动校准，已采集 " + count + "/" + ENROLLMENT_SAMPLES);
        if (count >= ENROLLMENT_SAMPLES) {
            IdentityModel enrolled = IdentityModel.enroll(enrollmentSamples,
                    enrollmentGeometrySamples);
            TemplateStore.saveIdentityModel(this, enrolled);
            finishSuccess(enrolled.textureThreshold);
        }
    }

    private void processRecognition(float[] descriptor, float[] geometry) {
        float frameThreshold = Math.max(0.24f,
                recognitionThreshold - posePenalty() - qualityPenalty() - framingPenalty());
        IdentityModel.Match match = identityModel.compare(descriptor, geometry, frameThreshold);
        float score = match.textureScore;
        lastFrontScore = score;
        lastGeometryScore = match.geometryScore;
        lastGeometryPeak = match.geometryPeak;
        lastConsistentCells = match.consistentCells;
        lastConsistentCoreCells = match.consistentCoreCells;
        bestScore = Math.min(bestScore, score);
        boolean matched = match.accepted;
        RecognitionStabilizer.Result result = recognitionStabilizer.add(
                score, frameThreshold, recognitionThreshold, matched);
        consecutiveMatches = result.matches;

        String state = matched
                ? "正在确认 " + result.matches + "/" + REQUIRED_MATCHES
                : "纹理或脸型未匹配，请自然正视屏幕";
        updateDetail(String.format(Locale.CHINA, "%s · 匹配 %.3f / %.3f",
                state, score, frameThreshold));
        logDiagnostic(score, score, matched, "match");
        if (result.confirmed) {
            finishSuccess(result.meanScore);
        }
    }

    private void updateEyeState(Face face) {
        Float left = face.getLeftEyeOpenProbability();
        Float right = face.getRightEyeOpenProbability();
        lastLeftEyeProbability = left;
        lastRightEyeProbability = right;
        lastLeftEyeContourRatio = eyeContourRatio(face, FaceContour.LEFT_EYE);
        lastRightEyeContourRatio = eyeContourRatio(face, FaceContour.RIGHT_EYE);

        boolean rawEyesOpen = PassiveEyeGate.areBothEyesOpen(left, right,
                lastLeftEyeContourRatio, lastRightEyeContourRatio);
        if (rawEyesOpen) {
            consecutiveOpenEyeFrames++;
        } else {
            consecutiveOpenEyeFrames = 0;
        }
        // This is a passive quality check, not a blink challenge or spoof-proof liveness claim.
        eyesCurrentlyOpen = rawEyesOpen;
        lastYaw = face.getHeadEulerAngleY();
        lastPitch = face.getHeadEulerAngleX();
        lastRoll = face.getHeadEulerAngleZ();
    }

    private static float eyeContourRatio(Face face, int contourType) {
        FaceContour contour = face.getContour(contourType);
        if (contour == null || contour.getPoints().size() < 6) {
            return 0f;
        }
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (PointF point : contour.getPoints()) {
            minX = Math.min(minX, point.x);
            maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y);
            maxY = Math.max(maxY, point.y);
        }
        float width = maxX - minX;
        return width <= 0f ? 0f : (maxY - minY) / width;
    }

    private static String probabilityText(Float value) {
        return value == null ? "null" : String.format(Locale.US, "%.2f", value);
    }

    private static PointF landmarkPosition(Face face, int landmarkType) {
        FaceLandmark landmark = face.getLandmark(landmarkType);
        return landmark == null ? null : landmark.getPosition();
    }

    private boolean isWithinHardPose(Face face) {
        return Math.abs(face.getHeadEulerAngleY()) <= MAX_ABS_YAW_DEGREES
                && Math.abs(face.getHeadEulerAngleX()) <= MAX_ABS_PITCH_DEGREES
                && Math.abs(face.getHeadEulerAngleZ()) <= MAX_ABS_ROLL_DEGREES;
    }

    private String qualityProblem() {
        if (lastBrightness < MIN_BRIGHTNESS) {
            return "光线太暗，请稍微提高屏幕亮度";
        }
        if (lastBrightness > MAX_BRIGHTNESS) {
            return "画面过亮，请避开强光";
        }
        if (lastContrast < MIN_CONTRAST) {
            return "面部光线不足，请面向屏幕";
        }
        if (lastSharpness < MIN_SHARPNESS) {
            return "画面不够清晰，请保持手机稳定";
        }
        return null;
    }

    private float posePenalty() {
        float penalty = scaledPenalty(Math.abs(lastYaw), 14f, MAX_ABS_YAW_DEGREES, 0.014f)
                + scaledPenalty(Math.abs(lastPitch), 12f, MAX_ABS_PITCH_DEGREES, 0.010f)
                + scaledPenalty(Math.abs(lastRoll), 12f, MAX_ABS_ROLL_DEGREES, 0.008f);
        return Math.min(0.028f, penalty);
    }

    private float qualityPenalty() {
        float penalty = 0f;
        if (lastBrightness < 30f) {
            penalty += scaledPenalty(30f - lastBrightness, 0f, 18f, 0.010f);
        } else if (lastBrightness > 230f) {
            penalty += scaledPenalty(lastBrightness - 230f, 0f, 18f, 0.010f);
        }
        if (lastContrast < 12f) {
            penalty += scaledPenalty(12f - lastContrast, 0f, 6f, 0.012f);
        }
        if (lastSharpness < 3.2f) {
            penalty += scaledPenalty(3.2f - lastSharpness, 0f, 1.7f, 0.012f);
        }
        return Math.min(0.026f, penalty);
    }

    private float framingPenalty() {
        float penalty = 0f;
        if (lastFaceWidthRatio < 0.24f) {
            penalty += scaledPenalty(0.24f - lastFaceWidthRatio, 0f, 0.06f, 0.008f);
        }
        if (lastCenterOffsetX > 0.25f) {
            penalty += scaledPenalty(lastCenterOffsetX - 0.25f, 0f, 0.09f, 0.006f);
        }
        if (lastCenterOffsetY > 0.27f) {
            penalty += scaledPenalty(lastCenterOffsetY - 0.27f, 0f, 0.09f, 0.006f);
        }
        return Math.min(0.014f, penalty);
    }

    private static float scaledPenalty(float value, float freeLimit, float hardLimit,
            float maximumPenalty) {
        if (value <= freeLimit) {
            return 0f;
        }
        float range = Math.max(0.0001f, hardLimit - freeLimit);
        return Math.min(maximumPenalty,
                (value - freeLimit) / range * maximumPenalty);
    }

    private void rejectRecognitionFrame() {
        if (Constants.MODE_ENROLL.equals(mode)) {
            return;
        }
        RecognitionStabilizer.Result result = recognitionStabilizer.reject(recognitionThreshold);
        consecutiveMatches = result.matches;
    }

    private void resetFaceTracking() {
        consecutiveOpenEyeFrames = 0;
        eyesCurrentlyOpen = false;
        recognitionStabilizer.clear();
        consecutiveMatches = 0;
    }

    private void logDiagnostic(float score, float frontScore, boolean matched, String stage) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDiagnosticAt < 400L && consecutiveMatches < REQUIRED_MATCHES) {
            return;
        }
        lastDiagnosticAt = now;
        android.util.Log.d(TAG, String.format(Locale.US,
                "Frame stage=%s matched=%s score=%.3f front=%.3f pose=%.1f/%.1f/%.1f"
                        + " quality=%.1f/%.1f/%.1f eyes=%s/%s ratios=%.3f/%.3f"
                        + " geometry=%.2f/%.2f cells=%d/%d frames=%d/%d",
                stage, matched, score, frontScore, lastYaw, lastPitch, lastRoll,
                lastBrightness, lastContrast, lastSharpness,
                probabilityText(lastLeftEyeProbability),
                probabilityText(lastRightEyeProbability),
                lastLeftEyeContourRatio, lastRightEyeContourRatio,
                lastGeometryScore, lastGeometryPeak,
                lastConsistentCells, lastConsistentCoreCells,
                consecutiveMatches, processedFrameCount));
    }

    private void handleTimeout() {
        if (!finished.get()) {
            finishFailure(Constants.MODE_UNLOCK.equals(mode), "识别超时");
        }
    }

    private void finishSuccess(float score) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        resourceReleaseRequested.set(true);
        TemplateStore.recordSuccess(this);
        long now = SystemClock.elapsedRealtime();
        android.util.Log.i(TAG, "Recognition success, mode=" + mode
                + ", distance=" + score
                + ", frontDistance=" + lastFrontScore
                + ", geometry=" + lastGeometryScore + "/" + lastGeometryPeak
                + ", cells=" + lastConsistentCells + "/" + lastConsistentCoreCells
                + ", leftEye=" + probabilityText(lastLeftEyeProbability)
                + ", rightEye=" + probabilityText(lastRightEyeProbability)
                + ", eyeRatio=" + lastLeftEyeContourRatio + "/" + lastRightEyeContourRatio
                + ", stableOpen=" + consecutiveOpenEyeFrames
                + ", eyesOpen=" + eyesCurrentlyOpen
                + ", pose=" + lastYaw + "/" + lastPitch + "/" + lastRoll
                + ", quality=" + lastBrightness + "/" + lastContrast + "/" + lastSharpness
                + ", matches=" + consecutiveMatches + "/" + REQUIRED_MATCHES
                + ", sessionMs=" + (now - sessionStartedAt)
                + ", cameraMs=" + (firstFrameAt == 0L ? -1L : now - firstFrameAt)
                + ", processedFrames=" + processedFrameCount);
        runOnUiThread(() -> {
            stopCamera();
            deliverSuccess(score);
        });
    }

    private void deliverSuccess(float score) {
        if (Constants.MODE_UNLOCK.equals(mode)) {
            Intent result = new Intent(Constants.ACTION_UNLOCK_RESULT)
                    .setPackage(Constants.SYSTEM_UI_PACKAGE)
                    .putExtra(Constants.EXTRA_SESSION_TOKEN, sessionToken)
                    .putExtra(Constants.EXTRA_SUCCESS, true)
                    .putExtra(Constants.EXTRA_SCORE, score);
            sendBroadcast(result);
            // Keep this 1x1, non-touchable host alive while keyguard is still visible. Its
            // window is transparent, but finishing it here would make Android close the task,
            // briefly reveal the background application, then rebuild the lock-screen state.
            setResult(Activity.RESULT_OK, new Intent().putExtra(Constants.EXTRA_SCORE, score));
            mainHandler.post(keyguardExitWatcher);
            return;
        }
        Intent data = new Intent().putExtra(Constants.EXTRA_SCORE, score);
        setResult(Activity.RESULT_OK, data);
        finish();
        overridePendingTransition(0, 0);
    }

    private void finishFailure(boolean recordFailure, String message) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        resourceReleaseRequested.set(true);
        if (recordFailure) {
            TemplateStore.recordFailure(this);
        }
        android.util.Log.i(TAG, "Recognition finished without success, mode=" + mode
                + ", reason=" + message + ", bestDistance=" + bestScore);
        runOnUiThread(() -> {
            stopCamera();
            deliverFailure(message);
        });
    }

    private void deliverFailure(String message) {
        if (Constants.MODE_UNLOCK.equals(mode) && sessionToken != null) {
            Intent result = new Intent(Constants.ACTION_UNLOCK_RESULT)
                    .setPackage(Constants.SYSTEM_UI_PACKAGE)
                    .putExtra(Constants.EXTRA_SESSION_TOKEN, sessionToken)
                    .putExtra(Constants.EXTRA_SUCCESS, false);
            sendBroadcast(result);
        }
        Intent data = new Intent().putExtra(Constants.EXTRA_SCORE, bestScore)
                .putExtra("message", message);
        setResult(Activity.RESULT_CANCELED, data);
        finish();
        overridePendingTransition(0, 0);
    }

    private void stopCamera() {
        mainHandler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    @Override
    protected void onDestroy() {
        resourceReleaseRequested.set(true);
        mainHandler.removeCallbacks(keyguardExitWatcher);
        if (screenOffReceiver != null) {
            try {
                unregisterReceiver(screenOffReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver may already be gone during an abnormal Activity teardown.
            }
            screenOffReceiver = null;
        }
        stopCamera();
        if (!processing.get()) {
            releaseAnalyzerResources();
        }
        notifyCameraHostFinished();
        super.onDestroy();
    }

    private void releaseAnalyzerResources() {
        if (!resourcesReleased.compareAndSet(false, true)) {
            return;
        }
        synchronized (detectorLock) {
            if (detector != null) {
                detector.close();
                detector = null;
            }
        }
        analyzerExecutor.shutdown();
    }

    private void registerScreenOffReceiver() {
        screenOffReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    finished.set(true);
                    finishHostWithoutTransition();
                } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    finishHostWithoutTransition();
                }
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
    }

    private void finishHostWithoutTransition() {
        if (!isFinishing()) {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void notifyCameraHostFinished() {
        if (!Constants.MODE_UNLOCK.equals(mode) || sessionToken == null
                || !hostFinishedBroadcastSent.compareAndSet(false, true)) {
            return;
        }
        Intent finishedIntent = new Intent(Constants.ACTION_CAMERA_HOST_FINISHED)
                .setPackage(Constants.SYSTEM_UI_PACKAGE)
                .putExtra(Constants.EXTRA_SESSION_TOKEN, sessionToken);
        sendBroadcast(finishedIntent);
    }

    @Override
    public void onBackPressed() {
        finishFailure(false, "已取消");
        super.onBackPressed();
    }

    private Rect paddedAndClamped(Rect source, int width, int height) {
        int padX = Math.round(source.width() * 0.16f);
        int padTop = Math.round(source.height() * 0.20f);
        int padBottom = Math.round(source.height() * 0.10f);
        int left = Math.max(0, source.left - padX);
        int top = Math.max(0, source.top - padTop);
        int right = Math.min(width, source.right + padX);
        int bottom = Math.min(height, source.bottom + padBottom);
        return new Rect(left, top, right, bottom);
    }

    private void updateInstruction(String value) {
        if (instructionView != null) {
            runOnUiThread(() -> instructionView.setText(value));
        }
    }

    private void updateDetail(String value) {
        if (detailView != null) {
            runOnUiThread(() -> detailView.setText(value));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class FaceGuideView extends View {
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final android.graphics.PorterDuffXfermode clearXfermode =
                new android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.CLEAR);

        FaceGuideView(android.content.Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            shade.setColor(Color.argb(72, 0, 0, 0));
            border.setColor(Color.argb(230, 255, 255, 255));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(context.getResources().getDisplayMetrics().density * 3f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth() * 0.68f;
            float height = width * 1.28f;
            float left = (getWidth() - width) / 2f;
            float top = (getHeight() - height) / 2f;
            oval.set(left, top, left + width, top + height);
            canvas.drawColor(shade.getColor());
            shade.setXfermode(clearXfermode);
            canvas.drawOval(oval, shade);
            shade.setXfermode(null);
            canvas.drawOval(oval, border);
        }
    }
}

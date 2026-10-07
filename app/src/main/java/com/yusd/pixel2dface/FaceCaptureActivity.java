package com.yusd.pixel2dface;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.face.Face;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates one capture workflow; camera, measurements, policy and host have separate owners. */
public class FaceCaptureActivity extends ComponentActivity {
    private static final int REQUEST_CAMERA = 200;
    private static final long RECOGNITION_TIMEOUT_MS = 12_000;
    private static final long ENROLLMENT_TIMEOUT_MS = 45_000;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean outcomeLogged = new AtomicBoolean();
    private final Map<String, Integer> frameOutcomes = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FaceFrameExtractor extractor = new FaceFrameExtractor();
    private final EnrollmentSession enrollment = new EnrollmentSession();
    private final PassiveUnlockFailurePolicy passiveFailure = new PassiveUnlockFailurePolicy();
    private CameraCaptureSession camera;
    private CaptureContentView content;
    private UnlockCameraHost host;
    private RecognitionEngine recognition;
    private TemplateStore.RecognitionSettings settings;
    private String mode;
    private String sessionToken;
    private long generation;
    private long createdAt;
    private long engineReadyAt;
    private long firstFrameAt;
    private long firstFaceAt;
    private long firstQualityAt;
    private long firstMatchAt;
    private long lastUiUpdateAt;
    private long lastDiagnosticAt;
    private int processedFrames;
    private float bestScore = Float.MAX_VALUE;
    private boolean initialized;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createdAt = SystemClock.elapsedRealtime();
        mode = getIntent().getStringExtra(Constants.EXTRA_MODE);
        if (mode == null) mode = Constants.MODE_TEST;
        if ((!Constants.MODE_UNLOCK.equals(mode) && !Constants.MODE_ENROLL.equals(mode)
                && !Constants.MODE_TEST.equals(mode)) || isUnlock() != isExternalUnlockHost()) {
            finish(); return;
        }
        sessionToken = getIntent().getStringExtra(Constants.EXTRA_SESSION_TOKEN);
        android.util.Log.i("Pixel2DFace", "Capture created mode=" + mode);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finishFailure(false, "已取消"); }
        });
        if (!isUnlock()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            content = new CaptureContentView(this, isEnrollment(), () -> finishFailure(false, "已取消"));
            setContentView(content);
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) initialize();
        else if (isUnlock()) finishFailure(false, "未授予相机权限");
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    protected boolean isExternalUnlockHost() { return false; }
    private boolean isUnlock() { return Constants.MODE_UNLOCK.equals(mode); }
    private boolean isEnrollment() { return Constants.MODE_ENROLL.equals(mode); }

    @Override public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CAMERA || finished.get()) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) initialize();
        else finishFailure(false, "需要相机权限才能识别人脸");
    }

    private void initialize() {
        if (initialized || finished.get()) return;
        initialized = true;
        camera = new CameraCaptureSession(this, new CameraCaptureSession.Listener() {
            @Override public void onEngineReady(long elapsedMs) {
                engineReadyAt = elapsedMs;
                android.util.Log.i("Pixel2DFace", "Capture engine ready mode=" + mode
                        + " initMs=" + (elapsedMs - createdAt));
            }
            @Override public void onFrame(ImageProxy image, int rotation, List<Face> faces,
                    long capturedAtMs) { processFrame(image, rotation, faces, capturedAtMs); }
            @Override public void onRejectedFrame(String reason) {
                if (finished.get()) return;
                recordFrameOutcome("camera_" + reason);
                passiveFailure.breakContinuity();
                if (recognition != null) recognition.clear();
                showDetail("人脸检测暂时不可用，请保持手机稳定");
            }
            @Override public void onCameraError(String message) { finishFailure(false, message); }
        });
        // Include initialization in the deadline so a stalled provider cannot leave a host alive.
        main.postDelayed(() -> finishFailure(isUnlock(), "识别超时，请重试"),
                isEnrollment() ? ENROLLMENT_TIMEOUT_MS : RECOGNITION_TIMEOUT_MS);
        camera.execute(() -> {
            try {
                if (!isEnrollment()) {
                    settings = isUnlock()
                            ? TemplateStore.consumeAuthorizedRecognitionSettings(this, sessionToken)
                            : TemplateStore.loadRecognitionSettings(this);
                    if (settings == null) {
                        finishFailure(false, "识别会话未获授权、已过期或配置已变更"); return;
                    }
                    if (settings.model == null) {
                        finishFailure(false, "尚未录入有效人脸，请重新录入"); return;
                    }
                    if (isUnlock() && (!settings.state.enabled
                            || settings.state.lockoutUntil > System.currentTimeMillis())) {
                        finishFailure(false, "人脸解锁已停用或暂时锁定"); return;
                    }
                    recognition = new RecognitionEngine(settings.model, settings.threshold);
                    generation = recognition.beginSession(createdAt);
                }
                main.post(() -> {
                    if (finished.get() || isFinishing() || isDestroyed()) return;
                    if (isUnlock()) {
                        PowerManager power = getSystemService(PowerManager.class);
                        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                        if (power == null || !power.isInteractive() || keyguard == null
                                || !keyguard.isDeviceLocked()) { finishHost("inactive_at_start"); return; }
                        host = new UnlockCameraHost(this, sessionToken, this::finishHost);
                        host.attach();
                        android.util.Log.i("Pixel2DFace", "Capture host attached");
                    }
                    camera.execute(() -> {
                        try { camera.start(content == null ? null : content.preview); }
                        catch (RuntimeException failure) { finishFailure(false, "人脸引擎初始化失败"); }
                    });
                });
            } catch (RuntimeException failure) {
                android.util.Log.w("Pixel2DFace", "Capture initialization failed", failure);
                finishFailure(false, "人脸引擎初始化失败");
            }
        });
    }

    private void processFrame(ImageProxy image, int rotation, List<Face> faces, long capturedAt) {
        if (finished.get()) return;
        if (firstFrameAt == 0) firstFrameAt = SystemClock.elapsedRealtime();
        if (firstFaceAt == 0 && faces != null && !faces.isEmpty()) {
            firstFaceAt = SystemClock.elapsedRealtime();
        }
        processedFrames++;
        FaceFrameExtractor.Result frame = extractor.extract(image, rotation, faces);
        if (isUnlock() && passiveFailure.shouldStop(frame.reason, capturedAt)) {
            recordFrameOutcome("CLOSED_EYES_STOP");
            finishFailure(true, "持续闭眼，已停止本轮识别，请使用密码或重新亮屏");
            return;
        }
        if (frame.reason != FrameQualityPolicy.Reason.NONE) {
            recordFrameOutcome(frame.reason == FrameQualityPolicy.Reason.EYES_CLOSED
                    && frame.sample != null ? "EYE_" + PassiveEyeGate.problem(
                            frame.sample.leftEyeProbability, frame.sample.rightEyeProbability,
                            frame.sample.leftEyeContourRatio, frame.sample.rightEyeContourRatio).name()
                    : frame.reason.name());
            if (recognition != null) recognition.clear();
            showDetail(frame.reason.message); return;
        }
        if (firstQualityAt == 0) firstQualityAt = SystemClock.elapsedRealtime();
        if (isEnrollment()) {
            long evaluatedAt = SystemClock.elapsedRealtime();
            if (capturedAt > evaluatedAt
                    || evaluatedAt - capturedAt > RecognitionEngine.MAX_FRAME_AGE_MS) return;
            if (!enrollment.add(capturedAt, frame.sample, frame.texture, frame.geometry)) {
                showDetail("请自然正视镜头并保持稳定"); return;
            }
            showInstruction("录入人脸：自然看向屏幕即可");
            showDetail("正在自动校准，已采集 " + enrollment.count() + "/"
                    + EnrollmentSession.REQUIRED_SAMPLES);
            if (enrollment.isComplete() && !finished.get()) {
                IdentityModel model = enrollment.buildModel();
                // Claim the outcome before touching the existing enrollment. Cancellation
                // either wins this CAS and prevents the write, or observes a committed capture.
                if (!finished.compareAndSet(false, true)) return;
                boolean saved;
                try { saved = TemplateStore.saveIdentityModel(this, model); }
                catch (RuntimeException failure) { saved = false; }
                if (saved) publishSuccess(model.textureThreshold, false);
                else publishFailure("保存人脸失败，请重试");
            }
            return;
        }
        RecognitionEngine.Decision decision = recognition.recognize(generation, capturedAt,
                SystemClock.elapsedRealtime(), frame.sample, frame.texture, frame.geometry);
        if (decision.match.accepted && firstMatchAt == 0) firstMatchAt = SystemClock.elapsedRealtime();
        recordFrameOutcome(decision.reason == FrameQualityPolicy.Reason.NONE
                ? decision.votes.confirmed ? "CONFIRMED"
                : decision.votes.stable ? "MATCH_PENDING" : "MATCH_UNSTABLE"
                : decision.reason.name());
        if (Float.isFinite(decision.match.textureScore)) bestScore = Math.min(bestScore, decision.match.textureScore);
        showDetail(decision.reason == FrameQualityPolicy.Reason.NONE
                ? "正在确认 " + decision.votes.matches + "/" + RecognitionStabilizer.REQUIRED_MATCHES
                : decision.reason.message);
        if (decision.votes.confirmed) finishSuccess(decision.votes.meanScore, decision.votes.fastPath);
    }

    /** Runs on the analysis lane; validation and persistence never block the main thread. */
    private void finishSuccess(float score, boolean fastPath) {
        if (finished.get()) return;
        if (isUnlock() && !TemplateStore.validateAndRecordSuccess(this, settings)) {
            finishFailure(false, "配置已变更或无法保存，请重新亮屏识别"); return;
        }
        if (!finished.compareAndSet(false, true)) return;
        publishSuccess(score, fastPath);
    }

    private void publishSuccess(float score, boolean fastPath) {
        long now = SystemClock.elapsedRealtime();
        logOutcome("success");
        // Release logs contain timings only, without identity/geometry or per-eye measurements.
        android.util.Log.i("Pixel2DFace", "Capture completed mode=" + mode
                + " path=" + (fastPath ? "fast" : "normal")
                + " initMs=" + (engineReadyAt - createdAt) + " totalMs=" + (now - createdAt)
                + " firstResultMs=" + sinceCreation(firstFrameAt)
                + " firstFaceMs=" + sinceCreation(firstFaceAt)
                + " firstQualityMs=" + sinceCreation(firstQualityAt)
                + " firstMatchMs=" + sinceCreation(firstMatchAt)
                + " confirmationMs=" + (firstMatchAt == 0 ? -1 : now - firstMatchAt)
                + " frames=" + processedFrames);
        main.post(() -> {
            stopCapture();
            if (isFinishing() || isDestroyed()) return;
            Intent data = new Intent().putExtra(Constants.EXTRA_SCORE, score);
            if (isUnlock()) {
                PowerManager power = getSystemService(PowerManager.class);
                KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                if (power == null || !power.isInteractive() || keyguard == null
                        || !keyguard.isDeviceLocked()) { finishHost("inactive_after_success"); return; }
                if (host != null) host.awaitKeyguardDismissal();
                sendBroadcast(new Intent(Constants.ACTION_UNLOCK_RESULT).setPackage(Constants.SYSTEM_UI_PACKAGE)
                        .putExtra(Constants.EXTRA_SESSION_TOKEN, sessionToken)
                        .putExtra(Constants.EXTRA_SUCCESS, true).putExtra(Constants.EXTRA_SCORE, score)
                        .putExtra(Constants.EXTRA_STORE_REVISION, settings.state.revision));
                setResult(Activity.RESULT_OK, data);
            } else {
                setResult(Activity.RESULT_OK, data); finish(); overridePendingTransition(0, 0);
            }
        });
    }

    private void finishFailure(boolean recordFailure, String message) {
        if (!finished.compareAndSet(false, true)) return;
        if (recordFailure) StoreTasks.execute(() -> TemplateStore.tryRecordFailure(this));
        publishFailure(message);
    }

    private void publishFailure(String message) {
        logOutcome("failure: " + message);
        main.post(() -> {
            if (host != null) host.releaseLockScreen();
            stopCapture();
            if (isFinishing() || isDestroyed()) return;
            if (isUnlock() && sessionToken != null) sendBroadcast(new Intent(Constants.ACTION_UNLOCK_RESULT)
                    .setPackage(Constants.SYSTEM_UI_PACKAGE).putExtra(Constants.EXTRA_SESSION_TOKEN, sessionToken)
                    .putExtra(Constants.EXTRA_SUCCESS, false));
            setResult(Activity.RESULT_CANCELED, new Intent().putExtra(Constants.EXTRA_SCORE, bestScore)
                    .putExtra("message", message));
            if (host != null) host.close();
            finish(); overridePendingTransition(0, 0);
        });
    }

    private void stopCapture() {
        main.removeCallbacksAndMessages(null);
        if (recognition != null) recognition.endSession();
        if (camera != null) camera.close();
    }
    private void finishHost() {
        finishHost("host_dismissed");
    }
    private void finishHost(String reason) {
        logOutcome(reason);
        finished.set(true);
        if (host != null) host.close();
        stopCapture();
        if (!isFinishing()) { finish(); overridePendingTransition(0, 0); }
    }
    @Override protected void onStop() {
        if (camera != null && !finished.get()) finishHost("activity_stopped");
        super.onStop();
    }
    @Override protected void onDestroy() {
        logOutcome("activity_destroyed");
        finished.set(true); stopCapture();
        if (host != null) host.close();
        super.onDestroy();
    }

    /** Fixed reason names and timings only; never logs descriptors, scores or eye measurements. */
    private void recordFrameOutcome(String reason) {
        long now = SystemClock.elapsedRealtime();
        synchronized (frameOutcomes) {
            Integer count = frameOutcomes.get(reason);
            frameOutcomes.put(reason, count == null ? 1 : count + 1);
            if (isUnlock() && now - lastDiagnosticAt >= 2_000) {
                lastDiagnosticAt = now;
                android.util.Log.i("Pixel2DFace", "Capture progress elapsedMs="
                        + (now - createdAt) + " outcomes=" + frameOutcomes);
            }
        }
    }

    private void logOutcome(String outcome) {
        if (!outcomeLogged.compareAndSet(false, true)) return;
        synchronized (frameOutcomes) {
            android.util.Log.i("Pixel2DFace", "Capture ended mode=" + mode + " outcome="
                    + outcome + " elapsedMs=" + (SystemClock.elapsedRealtime() - createdAt)
                    + " firstResultMs=" + sinceCreation(firstFrameAt)
                    + " firstFaceMs=" + sinceCreation(firstFaceAt)
                    + " firstQualityMs=" + sinceCreation(firstQualityAt)
                    + " firstMatchMs=" + sinceCreation(firstMatchAt)
                    + " outcomes=" + frameOutcomes);
        }
    }

    private long sinceCreation(long timestamp) {
        return timestamp == 0 ? -1 : timestamp - createdAt;
    }

    private void showInstruction(String message) {
        if (content != null) main.post(() -> {
            if (!finished.get() && !isDestroyed()) content.showInstruction(message);
        });
    }
    private void showDetail(String message) {
        if (content == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdateAt < 120) return;
        lastUiUpdateAt = now;
        main.post(() -> { if (!finished.get() && !isDestroyed()) content.showDetail(message); });
    }
}

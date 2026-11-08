package com.yusd.pixel2dface;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.camera.core.ImageProxy;
import androidx.core.view.WindowCompat;
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
    private final DeviceCredentialConfirmation credentials = new DeviceCredentialConfirmation(this);
    private final Map<String, Integer> frameOutcomes = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FaceFrameExtractor extractor = new FaceFrameExtractor();
    private final EmbeddingEnrollment enrollment = new EmbeddingEnrollment();
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
    private boolean showingResult;
    private boolean resultSuccess;
    private String resultMessage = "";
    private float resultScore;

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
        if (isUnlock()) {
            if (!UnlockSessionPolicy.validToken(sessionToken)) { finish(); return; }
            // Keyguard may stop an ordinary Activity before asynchronous template/model
            // loading completes. Prepare the window and cancellation now; camera access and
            // the host-ready signal still require consuming the private launch grant.
            host = new UnlockCameraHost(this, sessionToken, this::finishHost);
            host.prepareWindow();
            android.util.Log.i("Pixel2DFace", "Capture lockscreen window prepared");
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (showingResult) finish(); else finishHost("user_cancelled");
            }
        });
        if (!isUnlock()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().setNavigationBarContrastEnforced(false);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightStatusBars(false);
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightNavigationBars(false);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            content = new CaptureContentView(this, isEnrollment(), () -> finishHost("user_cancelled"));
            setContentView(content);
            if (savedInstanceState != null && savedInstanceState.getBoolean("showing_result")) {
                finished.set(true);
                showCaptureResult(savedInstanceState.getBoolean("result_success"),
                        savedInstanceState.getString("result_message", ""),
                        savedInstanceState.getFloat("result_score"));
                return;
            }
        }
        if (isEnrollment()) {
            // Never restore an enrollment's authorization after process death or recreation.
            if (savedInstanceState != null) { finishHost("enrollment_recreated"); return; }
            credentials.request("确认录入人脸", this::requestCamera,
                    () -> finishHost("credential_cancelled"));
        } else {
            requestCamera();
        }
    }

    private void requestCamera() {
        if (finished.get()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) initialize();
        else if (isUnlock()) finishFailure(false, "未授予相机权限");
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (credentials.onActivityResult(requestCode, resultCode)) return;
        if (requestCode == REQUEST_CAMERA && showingResult
                && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            restartCapture();
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
                if (recognition != null) { recognition.clear(); showProgress(0); }
                showDetail("人脸检测暂时不可用，请保持手机稳定");
            }
            @Override public void onClosed() { extractor.close(); }
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
                extractor.initialize(this, isEnrollment() || settings.model instanceof EmbeddingIdentity);
                main.post(() -> {
                    if (finished.get() || isFinishing() || isDestroyed()) return;
                    if (isUnlock()) {
                        PowerManager power = getSystemService(PowerManager.class);
                        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
                        if (power == null || !power.isInteractive() || keyguard == null
                                || !keyguard.isDeviceLocked()) { finishHost("inactive_at_start"); return; }
                        host.attach();
                        android.util.Log.i("Pixel2DFace", "Capture host attached");
                    }
                    camera.execute(() -> {
                        try { camera.start(content == null ? null : content.preview); }
                        catch (RuntimeException failure) { finishFailure(false, "人脸引擎初始化失败"); }
                    });
                });
            } catch (Exception | LinkageError failure) {
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
            recordFrameOutcome((frame.reason == FrameQualityPolicy.Reason.EYES_CLOSED
                    || frame.reason == FrameQualityPolicy.Reason.EYES_UNAVAILABLE)
                    && frame.sample != null ? "EYE_" + PassiveEyeGate.problem(
                            frame.sample.leftEyeProbability, frame.sample.rightEyeProbability,
                            frame.sample.leftEyeContourRatio, frame.sample.rightEyeContourRatio).name()
                    : frame.reason.name());
            if (recognition != null) { recognition.clear(); showProgress(0); }
            showDetail(frame.reason.message); return;
        }
        if (firstQualityAt == 0) firstQualityAt = SystemClock.elapsedRealtime();
        if (isEnrollment()) {
            long evaluatedAt = SystemClock.elapsedRealtime();
            if (capturedAt > evaluatedAt
                    || evaluatedAt - capturedAt > RecognitionEngine.MAX_FRAME_AGE_MS) return;
            if (!enrollment.add(capturedAt, frame.sample, frame.texture)) {
                showDetail("请自然正视镜头并保持稳定"); return;
            }
            showDetail("保持自然，正在记住你的面容");
            showProgress(enrollment.count() / (float) EmbeddingIdentity.SAMPLE_COUNT);
            if (enrollment.isComplete() && !finished.get()) {
                IdentityTemplate model = enrollment.buildModel();
                // Claim the outcome before touching the existing enrollment. Cancellation
                // either wins this CAS and prevents the write, or observes a committed capture.
                if (!finished.compareAndSet(false, true)) return;
                boolean saved;
                try { saved = TemplateStore.saveIdentityModel(this, model); }
                catch (RuntimeException failure) { saved = false; }
                if (saved) publishSuccess(model.maximumThreshold(), false);
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
        if (Float.isFinite(decision.match.score)) bestScore = Math.min(bestScore, decision.match.score);
        showDetail(decision.reason == FrameQualityPolicy.Reason.NONE
                ? "保持片刻"
                : decision.reason.message);
        showProgress(decision.votes.confirmed ? 1f
                : Math.min(.9f, decision.votes.matches / (float) RecognitionStabilizer.REQUIRED_MATCHES));
        if (decision.votes.confirmed) finishSuccess(decision.votes.meanScore, decision.votes.fastPath);
    }

    /** Chooses the outcome on the analysis lane; all recognition writes share the disk queue. */
    private void finishSuccess(float score, boolean fastPath) {
        // Cancellation and success must choose one outcome before changing failure counters.
        if (!finished.compareAndSet(false, true)) return;
        if (!isUnlock()) {
            publishSuccess(score, fastPath);
            return;
        }
        // A timeout from the previous capture may still be queued. Preserve outcome order:
        // it must run before this reset, and a fifth failure must retain its lockout.
        StoreTasks.execute(() -> {
            if (!TemplateStore.validateAndRecordSuccess(this, settings)) {
                publishFailure("配置已变更或无法保存，请重新亮屏识别"); return;
            }
            publishSuccess(score, fastPath);
        });
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
                showCaptureResult(true, "", score);
            }
        });
    }

    private void finishFailure(boolean recordFailure, String message) {
        if (!finished.compareAndSet(false, true)) return;
        if (recordFailure) {
            TemplateStore.RecognitionSettings failedSettings = settings;
            StoreTasks.execute(() -> TemplateStore.validateAndRecordFailure(this, failedSettings));
        }
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
            if (!isUnlock()) {
                showCaptureResult(false, message, bestScore);
                return;
            }
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

    private void showCaptureResult(boolean success, String message, float score) {
        showingResult = true;
        resultSuccess = success;
        resultMessage = message;
        resultScore = score;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setResult(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
                new Intent().putExtra(Constants.EXTRA_SCORE, score).putExtra("message", message));
        boolean permissionMissing = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        content.showResult(success, isEnrollment(), message,
                permissionMissing ? "开启相机权限" : "再试一次", () -> {
                    if (permissionMissing) startActivityForResult(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())), REQUEST_CAMERA);
                    else restartCapture();
                }, this::finish);
    }

    private void restartCapture() {
        // Each retry owns a fresh camera, enrollment and credential challenge. Forward
        // the original caller so attempts do not accumulate in the back stack.
        startActivity(new Intent(this, FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, mode).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT));
        finish();
    }

    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (showingResult) {
            state.putBoolean("showing_result", true);
            state.putBoolean("result_success", resultSuccess);
            state.putString("result_message", resultMessage);
            state.putFloat("result_score", resultScore);
        }
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
        credentials.cancel();
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

    private void showProgress(float progress) {
        if (content != null) main.post(() -> {
            if (!finished.get() && !isDestroyed()) content.showProgress(progress);
        });
    }
    private void showDetail(String message) {
        if (content == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdateAt < 350) return;
        lastUiUpdateAt = now;
        main.post(() -> { if (!finished.get() && !isDestroyed()) content.showDetail(message); });
    }
}

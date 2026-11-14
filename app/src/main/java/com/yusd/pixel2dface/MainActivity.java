package com.yusd.pixel2dface;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import java.util.function.BooleanSupplier;

public final class MainActivity extends ComponentActivity {
    private static final int REQUEST_ENROLL = 100, REQUEST_TEST = 101, REQUEST_CAMERA = 102;
    private static final boolean VALIDATION = "validation".equals(BuildConfig.BUILD_TYPE);
    private final DeviceCredentialConfirmation credentials = new DeviceCredentialConfirmation(this);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (displayedState != null) renderSummary();
            main.postDelayed(this, 1000);
        }
    };
    private TextView statusView;
    private View faceSection;
    private MaterialSwitch enabledSwitch;
    private MaterialButton primaryButton, enrollButton, testButton, deleteButton, previewButton;
    private MaterialButton animationStyleButton;
    private MaterialButton strictRecognitionButton, balancedRecognitionButton, comfortRecognitionButton;
    private MaterialButtonToggleGroup profileChooser;
    private TemplateStore.StateSnapshot displayedState;
    private long heartbeat;
    private int hookVersion;
    private boolean rendering, saving, resumed;
    private int refreshGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setNavigationBarContrastEnforced(false);
        boolean lightBars = getResources().getBoolean(R.bool.light_system_bars);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(lightBars);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(lightBars);
        setTitle(R.string.app_name);
        setContentView(buildContent());
        setControlsEnabled(false);
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        refreshState();
        main.post(statusTick);
    }
    @Override protected void onPause() {
        resumed = false;
        refreshGeneration++;
        main.removeCallbacks(statusTick);
        super.onPause();
    }
    @Override protected void onDestroy() {
        credentials.cancel();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setBackgroundColor(getColor(R.color.page));
        LinearLayout root = Ui.column(this);
        root.setPadding(dp(24), dp(16), dp(24), dp(32));
        scroll.addView(root);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        scroll.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            int available = r - l - scroll.getPaddingLeft() - scroll.getPaddingRight();
            int edge = Math.max(dp(24), (available - dp(560)) / 2);
            if (root.getPaddingLeft() != edge) root.setPadding(edge, dp(16), edge, dp(32));
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.heading(this, getString(R.string.app_name), 30),
                new LinearLayout.LayoutParams(0, -2, 1));
        MaterialButton about = Ui.button(this, "", false);
        about.setContentDescription("连接与关于");
        about.setIconResource(R.drawable.ic_info);
        about.setIconPadding(0);
        about.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        about.setStrokeWidth(0);
        about.setPadding(0, 0, 0, 0);
        about.setOnClickListener(v -> showDiagnostics());
        header.addView(about, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, Ui.space(this, 0, 24));
        LinearLayout hero = Ui.column(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(24), dp(30), dp(24), dp(24));
        hero.setBackground(Ui.rounded(this, R.color.accent_surface, 30));
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.ic_face_unlock);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hero.addView(face, new LinearLayout.LayoutParams(dp(60), dp(60)));
        statusView = Ui.heading(this, "加载中", 25);
        statusView.setTextColor(getColor(R.color.on_accent_surface));
        statusView.setGravity(Gravity.CENTER);
        hero.addView(statusView, Ui.space(this, 22, 22));
        primaryButton = Ui.button(this, "录入面容", true);
        primaryButton.setOnClickListener(v -> performPrimaryAction());
        hero.addView(primaryButton, new LinearLayout.LayoutParams(-1, -2));
        root.addView(hero, Ui.space(this, 0, 24));
        LinearLayout faceSettings = section(root, "面容");
        faceSection = (View) faceSettings.getParent();
        enabledSwitch = new MaterialSwitch(this);
        enabledSwitch.setText("人脸解锁");
        enabledSwitch.setTextSize(17);
        enabledSwitch.setMinHeight(dp(60));
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (rendering) return;
            rendering = true;
            button.setChecked(displayedState.enabled);
            rendering = false;
            saveProtectedSetting(() -> TemplateStore.trySetEnabled(this, checked));
        });
        if (!VALIDATION) faceSettings.addView(enabledSwitch);
        testButton = action(faceSettings, "测试识别", () -> startCapture(Constants.MODE_TEST, REQUEST_TEST));
        LinearLayout management = new LinearLayout(this);
        enrollButton = Ui.button(this, "重新录入", false);
        enrollButton.setStrokeWidth(0);
        enrollButton.setOnClickListener(v -> startCapture(Constants.MODE_ENROLL, REQUEST_ENROLL));
        management.addView(enrollButton, new LinearLayout.LayoutParams(0, -2, 1));
        deleteButton = Ui.button(this, "删除面容", false);
        deleteButton.setStrokeWidth(0);
        deleteButton.setOnClickListener(v -> confirmDeletion());
        management.addView(deleteButton, new LinearLayout.LayoutParams(0, -2, 1));
        faceSettings.addView(management, Ui.space(this, 2, 2));
        deleteButton.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{
                (getColor(R.color.muted) & 0x00FFFFFF) | 0x61000000, getColor(R.color.danger)}));
        LinearLayout recognition = section(root, "识别模式");
        profileChooser = chooser();
        strictRecognitionButton = choice(profileChooser, "严格", () -> selectRecognitionProfile(0));
        balancedRecognitionButton = choice(profileChooser, "均衡", () -> selectRecognitionProfile(1));
        comfortRecognitionButton = choice(profileChooser, "便捷", () -> selectRecognitionProfile(2));
        recognition.addView(profileChooser);
        if (!VALIDATION) {
            LinearLayout appearance = section(root, "锁屏动画");
            animationStyleButton = action(appearance, "选择动画", () -> AnimationGallery.show(
                    this, displayedState.animationStyle, this::selectAnimationStyle));
            animationStyleButton.setIconResource(R.drawable.ic_styles);
            animationStyleButton.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            previewButton = action(appearance, "预览动画", this::showAnimationPreview);
        }
        return scroll;
    }
    private LinearLayout section(LinearLayout root, String label) {
        LinearLayout section = Ui.column(this);
        section.addView(Ui.heading(this, label, 18), Ui.space(this, 0, 12));
        LinearLayout content = Ui.column(this);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        content.setBackground(Ui.rounded(this, R.color.surface, 24));
        section.addView(content);
        root.addView(section, Ui.space(this, 0, 24));
        return content;
    }
    private MaterialButton action(LinearLayout parent, String label, Runnable action) {
        MaterialButton button = Ui.button(this, label, false);
        button.setStrokeWidth(0);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPaddingRelative(0, dp(10), 0, dp(10));
        button.setOnClickListener(v -> action.run());
        parent.addView(button, Ui.space(this, 2, 2));
        return button;
    }
    private MaterialButtonToggleGroup chooser() {
        MaterialButtonToggleGroup chooser = new MaterialButtonToggleGroup(this);
        chooser.setSingleSelection(true);
        chooser.setSelectionRequired(true);
        chooser.setPadding(0, dp(8), 0, dp(8));
        return chooser;
    }
    private MaterialButton choice(MaterialButtonToggleGroup chooser, String label, Runnable action) {
        MaterialButton button = Ui.button(this, label, false);
        button.setCheckable(true);
        button.setPaddingRelative(dp(8), dp(12), dp(8), dp(12));
        button.setOnClickListener(v -> action.run());
        chooser.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        return button;
    }

    private void refreshState() {
        int request = ++refreshGeneration;
        StoreTasks.execute(() -> {
            TemplateStore.StateSnapshot state = TemplateStore.readState(this);
            long lastHeartbeat = TemplateStore.getHookHeartbeat(this);
            int runtimeVersion = HookRuntimeStore.moduleVersion(this);
            runOnUiThread(() -> {
                if (!resumed || isFinishing() || isDestroyed() || request != refreshGeneration) return;
                displayedState = state;
                heartbeat = lastHeartbeat;
                hookVersion = runtimeVersion;
                renderState();
                setControlsEnabled(!saving && !credentials.isPending());
            });
        });
    }
    private boolean cameraGranted() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    private boolean deviceSecure() { return getSystemService(KeyguardManager.class).isDeviceSecure(); }
    private boolean connectedThisBoot() {
        long age = System.currentTimeMillis() - heartbeat;
        return heartbeat > 0 && age >= 0 && age <= SystemClock.elapsedRealtime();
    }
    private boolean updatePending() {
        return connectedThisBoot() && hookVersion != BuildConfig.VERSION_CODE;
    }
    private void renderState() {
        faceSection.setVisibility(displayedState.enrolled || displayedState.legacyTemplates
                || !displayedState.storageAvailable ? View.VISIBLE : View.GONE);
        rendering = true;
        enabledSwitch.setChecked(displayedState.enabled && displayedState.enrolled);
        rendering = false;
        enrollButton.setText(displayedState.enrolled ? "重新录入" : "录入面容");
        profileChooser.check((displayedState.recognitionProfile == 0 ? strictRecognitionButton
                : displayedState.recognitionProfile == 2 ? comfortRecognitionButton
                : balancedRecognitionButton).getId());
        if (!VALIDATION) animationStyleButton.setText(AnimationStyle.name(displayedState.animationStyle));
        renderSummary();
    }
    private void renderSummary() {
        String title, action;
        if (!deviceSecure()) { title = "未设置锁屏"; action = "设置锁屏"; }
        else if (!displayedState.storageAvailable) { title = "面容数据异常"; action = "删除面容"; }
        else if (!displayedState.enrolled) {
            title = displayedState.legacyTemplates ? "需重新录入" : "未录入面容";
            action = "录入面容";
        } else if (!cameraGranted()) { title = "相机未授权"; action = "授权相机"; }
        else if (VALIDATION) { title = "面容已录入"; action = "测试识别"; }
        else if (!displayedState.enabled) { title = "未启用"; action = "启用解锁"; }
        else if (displayedState.lockoutUntil > System.currentTimeMillis()) {
            long seconds = (displayedState.lockoutUntil - System.currentTimeMillis() + 999) / 1000;
            title = seconds < 60 ? seconds + " 秒后恢复" : "暂时无法解锁";
            action = "测试识别";
        } else if (!connectedThisBoot()) { title = "模块未连接"; action = "查看连接"; }
        else if (updatePending()) { title = "重启后生效"; action = "查看更新"; }
        else { title = "已启用"; action = "测试识别"; }
        if (!statusView.getText().toString().equals(title)) statusView.setText(title);
        if (!primaryButton.getText().toString().equals(action)) primaryButton.setText(action);
        testButton.setVisibility(displayedState.enrolled && cameraGranted()
                && !"测试识别".equals(action) ? View.VISIBLE : View.GONE);
    }
    private void performPrimaryAction() {
        if (!deviceSecure()) startActivity(new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD));
        else if (!displayedState.storageAvailable) confirmDeletion();
        else if (!displayedState.enrolled)
            startCapture(Constants.MODE_ENROLL, REQUEST_ENROLL);
        else if (!cameraGranted()) requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        else if (!VALIDATION && !displayedState.enabled)
            saveProtectedSetting(() -> TemplateStore.trySetEnabled(this, true));
        else if (!VALIDATION && (!connectedThisBoot() || updatePending())
                && displayedState.lockoutUntil <= System.currentTimeMillis()) showDiagnostics();
        else startCapture(Constants.MODE_TEST, REQUEST_TEST);
    }
    private void confirmDeletion() {
        new MaterialAlertDialogBuilder(this).setTitle("删除面容？")
                .setMessage("删除后将关闭人脸解锁")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) ->
                        saveProtectedSetting(() -> TemplateStore.tryClearTemplates(this))).show();
    }
    private void selectRecognitionProfile(int profile) {
        renderState(); // Keep the saved selection until device credentials confirm the change.
        if (profile != displayedState.recognitionProfile)
            saveProtectedSetting(() -> TemplateStore.trySetRecognitionProfile(this, profile));
    }
    private void selectAnimationStyle(int style) {
        renderState();
        if (style != displayedState.animationStyle)
            saveSetting(() -> TemplateStore.trySetAnimationStyle(this, style));
    }
    private void showAnimationPreview() {
        AnimationGallery.preview(this, displayedState.animationStyle);
    }
    private void showDiagnostics() {
        String details = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME;
        if (!VALIDATION) {
            if (!connectedThisBoot()) {
                details += "\n\n锁屏模块未连接\n在 LSPosed 启用模块，勾选「系统界面」后重启手机。";
            } else if (updatePending()) {
                details += "\n\n重启手机以更新锁屏模块。";
            } else {
                details += "\n\n锁屏模块已连接\n运行版本 " + hookVersion;
            }
        }
        details += "\n\n面容仅在本机处理。照片或视频也可能通过识别。";
        new MaterialAlertDialogBuilder(this).setTitle("连接与关于").setMessage(details)
                .setNeutralButton("刷新", (dialog, which) -> refreshState())
                .setPositiveButton("完成", null).show();
    }
    private void startCapture(String mode, int requestCode) {
        startActivityForResult(new Intent(this, FaceCaptureActivity.class)
                .putExtra(Constants.EXTRA_MODE, mode), requestCode);
    }
    private void saveProtectedSetting(BooleanSupplier write) {
        if (saving || credentials.isPending()) return;
        setControlsEnabled(false);
        credentials.request("确认修改设置", () -> saveSetting(write, true), this::refreshState);
    }
    private void saveSetting(BooleanSupplier write) {
        saveSetting(write, false);
    }
    private void saveSetting(BooleanSupplier write, boolean protectedSetting) {
        if (saving) return;
        saving = true;
        setControlsEnabled(false);
        StoreTasks.execute(() -> {
            boolean saved = write.getAsBoolean();
            runOnUiThread(() -> {
                saving = false;
                if (!resumed || isFinishing() || isDestroyed()) return;
                if (!saved) Snackbar.make(primaryButton, "保存失败，原设置已保留", Snackbar.LENGTH_LONG)
                        .setAction("重试", v -> {
                            if (protectedSetting) saveProtectedSetting(write);
                            else saveSetting(write);
                        }).show();
                refreshState();
            });
        });
    }
    private void setControlsEnabled(boolean enabled) {
        boolean enrolled = displayedState != null && displayedState.enrolled;
        primaryButton.setEnabled(enabled);
        enabledSwitch.setEnabled(enabled && enrolled
                && (displayedState.enabled || (deviceSecure() && cameraGranted())));
        enrollButton.setEnabled(enabled && deviceSecure()
                && displayedState != null && displayedState.storageAvailable);
        testButton.setEnabled(enabled && enrolled && cameraGranted());
        deleteButton.setEnabled(enabled && displayedState != null
                && (enrolled || displayedState.legacyTemplates || !displayedState.storageAvailable));
        strictRecognitionButton.setEnabled(enabled);
        balancedRecognitionButton.setEnabled(enabled);
        comfortRecognitionButton.setEnabled(enabled);
        if (!VALIDATION) {
            animationStyleButton.setEnabled(enabled);
            previewButton.setEnabled(enabled);
        }
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (credentials.onActivityResult(requestCode, resultCode)) return;
        refreshState();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_CAMERA) return;
        if (!cameraGranted() && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
            new MaterialAlertDialogBuilder(this).setTitle("相机未授权")
                    .setNegativeButton("返回", null)
                    .setPositiveButton("前往设置", (dialog, which) -> startActivity(new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                    .show();
        refreshState();
    }
    private int dp(int value) { return Ui.dp(this, value); }
}

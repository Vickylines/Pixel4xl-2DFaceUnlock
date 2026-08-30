package com.yusd.pixel2dface;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_ENROLL = 100;
    private static final int REQUEST_TEST = 101;

    private TextView statusView;
    private Switch enabledSwitch;
    private Button enrollButton;
    private Button testButton;
    private Button faceIdStyleButton;
    private Button islandStyleButton;
    private TextView animationStyleSummary;
    private Button strictRecognitionButton;
    private Button balancedRecognitionButton;
    private Button comfortRecognitionButton;
    private TextView recognitionProfileSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Pixel 2D 人脸解锁");
        setContentView(buildContent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private View buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(247, 249, 252));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Pixel 2D 人脸解锁", 28, Color.rgb(25, 32, 40));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(Build.MANUFACTURER + " " + Build.MODEL
                        + " · Android " + Build.VERSION.RELEASE
                        + " / API " + Build.VERSION.SDK_INT,
                14, Color.rgb(82, 94, 108));
        root.addView(subtitle, margins(0, 6, 0, 18));

        LinearLayout warning = card();
        TextView warningTitle = text("安全提示", 17, Color.rgb(130, 70, 0));
        warningTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        warning.addView(warningTitle);
        warning.addView(text("这是普通前置摄像头的弱生物识别。照片或视频仍可能绕过，"
                        + "仅用于日常锁屏解锁，不用于支付、银行或密码管理器。",
                14, Color.rgb(95, 64, 20)), margins(0, 6, 0, 0));
        warning.addView(text("适配目标为 Google Pixel 上的 Pixel/AOSP/LineageOS 类 SystemUI，"
                        + "Android 10–16 使用运行时探测；0.5.1 仅有当前设备真机基线，"
                        + "此 0.6.0 包及其他版本仍需逐机回归。",
                13, Color.rgb(110, 72, 20)), margins(0, 7, 0, 0));
        warning.setBackground(rounded(Color.rgb(255, 244, 219), 16));
        root.addView(warning, margins(0, 0, 0, 14));

        LinearLayout stateCard = card();
        statusView = text("正在检查模块状态…", 16, Color.rgb(35, 45, 55));
        stateCard.addView(statusView);
        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用锁屏 2D 人脸解锁");
        enabledSwitch.setTextSize(16);
        enabledSwitch.setPadding(0, dp(12), 0, 0);
        enabledSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
            if (buttonView.isPressed()) {
                if (checked && !TemplateStore.isEnrolled(this)) {
                    Toast.makeText(this, "请先录入人脸", Toast.LENGTH_SHORT).show();
                    buttonView.setChecked(false);
                } else {
                    TemplateStore.setEnabled(this, checked);
                    refreshState();
                }
            }
        });
        stateCard.addView(enabledSwitch);
        root.addView(stateCard, margins(0, 0, 0, 14));

        LinearLayout appearance = card();
        TextView appearanceTitle = text("锁屏动画", 18, Color.rgb(30, 40, 50));
        appearanceTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appearance.addView(appearanceTitle);
        appearance.addView(text("选择后会在下一次亮屏时生效，不影响识别和解锁逻辑。",
                13, Color.rgb(92, 103, 115)), margins(0, 5, 0, 12));

        LinearLayout styleChooser = new LinearLayout(this);
        styleChooser.setOrientation(LinearLayout.HORIZONTAL);
        faceIdStyleButton = styleButton("面容光环");
        islandStyleButton = styleButton("灵动岛");
        LinearLayout.LayoutParams firstStyleParams = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        firstStyleParams.setMarginEnd(dp(8));
        styleChooser.addView(faceIdStyleButton, firstStyleParams);
        styleChooser.addView(islandStyleButton, new LinearLayout.LayoutParams(
                0, dp(50), 1f));
        appearance.addView(styleChooser);

        animationStyleSummary = text("", 13, Color.rgb(72, 84, 96));
        appearance.addView(animationStyleSummary, margins(2, 10, 2, 0));
        faceIdStyleButton.setOnClickListener(v -> selectAnimationStyle(
                TemplateStore.ANIMATION_STYLE_FACE_ID));
        islandStyleButton.setOnClickListener(v -> selectAnimationStyle(
                TemplateStore.ANIMATION_STYLE_DYNAMIC_ISLAND));
        root.addView(appearance, margins(0, 0, 0, 14));

        LinearLayout actions = card();
        TextView faceTitle = text("人脸数据", 18, Color.rgb(30, 40, 50));
        faceTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        actions.addView(faceTitle);

        enrollButton = button("录入人脸");
        enrollButton.setOnClickListener(v -> startCapture(Constants.MODE_ENROLL, REQUEST_ENROLL));
        actions.addView(enrollButton, margins(0, 12, 0, 8));

        testButton = button("测试识别");
        testButton.setOnClickListener(v -> startCapture(Constants.MODE_TEST, REQUEST_TEST));
        actions.addView(testButton, margins(0, 0, 0, 8));

        Button deleteButton = button("删除人脸数据");
        deleteButton.setTextColor(Color.rgb(180, 35, 45));
        deleteButton.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("删除人脸数据？")
                .setMessage("删除后锁屏人脸解锁会立即停用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    TemplateStore.clearTemplates(this);
                    refreshState();
                })
                .show());
        actions.addView(deleteButton);
        root.addView(actions, margins(0, 0, 0, 14));

        LinearLayout options = card();
        TextView optionsTitle = text("识别设置", 18, Color.rgb(30, 40, 50));
        optionsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        options.addView(optionsTitle);

        TextView passiveTitle = text("无动作安全校验", 15, Color.rgb(45, 55, 65));
        passiveTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        options.addView(passiveTitle, margins(0, 14, 0, 4));
        options.addView(text("解锁时只需自然看向屏幕，不要求眨眼或转头。新版会按眼位对齐人脸，"
                        + "并在 6 帧窗口内要求至少 4 帧身份一致；偶发坏帧不再清空进度。",
                13, Color.rgb(92, 103, 115)));

        TextView profileTitle = text("安全偏好", 15, Color.rgb(45, 55, 65));
        profileTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        options.addView(profileTitle, margins(0, 16, 0, 8));
        LinearLayout profileChooser = new LinearLayout(this);
        profileChooser.setOrientation(LinearLayout.HORIZONTAL);
        strictRecognitionButton = styleButton("严格");
        balancedRecognitionButton = styleButton("均衡");
        comfortRecognitionButton = styleButton("便捷");
        LinearLayout.LayoutParams profileButtonParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        profileButtonParams.setMarginEnd(dp(6));
        profileChooser.addView(strictRecognitionButton, profileButtonParams);
        LinearLayout.LayoutParams balancedParams = new LinearLayout.LayoutParams(
                0, dp(48), 1f);
        balancedParams.setMarginEnd(dp(6));
        profileChooser.addView(balancedRecognitionButton, balancedParams);
        profileChooser.addView(comfortRecognitionButton, new LinearLayout.LayoutParams(
                0, dp(48), 1f));
        options.addView(profileChooser);
        recognitionProfileSummary = text("", 13, Color.rgb(92, 103, 115));
        options.addView(recognitionProfileSummary, margins(2, 9, 2, 0));
        strictRecognitionButton.setOnClickListener(v -> selectRecognitionProfile(
                TemplateStore.RECOGNITION_PROFILE_STRICT));
        balancedRecognitionButton.setOnClickListener(v -> selectRecognitionProfile(
                TemplateStore.RECOGNITION_PROFILE_BALANCED));
        comfortRecognitionButton.setOnClickListener(v -> selectRecognitionProfile(
                TemplateStore.RECOGNITION_PROFILE_COMFORT));
        options.addView(text("人脸距离会在录入时自动校准，并始终受安全上限约束；不再直接调系数。"
                        + "“便捷”仅放宽很小范围，不能把普通 2D 前摄变成 Face ID。",
                13, Color.rgb(100, 110, 120)), margins(0, 9, 0, 0));
        root.addView(options);

        TextView steps = text("安装后操作：在 LSPosed 中启用本模块，仅勾选“系统界面”作用域，"
                        + "然后重启手机或重启 SystemUI。重启后的第一次解锁仍需输入 PIN。",
                13, Color.rgb(85, 95, 105));
        steps.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(steps, margins(8, 18, 8, 0));
        return scrollView;
    }

    private void refreshState() {
        boolean enrolled = TemplateStore.isEnrolled(this);
        boolean legacyTemplates = TemplateStore.hasLegacyTemplates(this);
        boolean enabled = TemplateStore.isEnabled(this);
        long heartbeat = TemplateStore.getHookHeartbeat(this);
        boolean hookSeen = System.currentTimeMillis() - heartbeat < 24L * 60L * 60L * 1000L;
        String compatibility = TemplateStore.getHookCompatibility(this);
        statusView.setText((hookSeen ? "● LSPosed 钩子已加载" : "○ 等待 LSPosed 加载")
                + "\n" + (enrolled ? "已录入新版人脸"
                : legacyTemplates ? "检测到旧版人脸数据，请重新录入" : "尚未录入人脸")
                + (hookSeen && !compatibility.isEmpty() ? "\n\n" + compatibility : ""));
        statusView.setTextColor(hookSeen ? Color.rgb(20, 125, 70) : Color.rgb(120, 75, 20));
        enabledSwitch.setChecked(enabled && enrolled);
        enrollButton.setText(enrolled ? "重新录入人脸"
                : legacyTemplates ? "升级并重新录入" : "录入人脸");
        testButton.setEnabled(enrolled);
        updateAnimationStyleControls();
        updateRecognitionProfileControls();
    }

    private void selectAnimationStyle(int style) {
        TemplateStore.setAnimationStyle(this, style);
        updateAnimationStyleControls();
        String name = style == TemplateStore.ANIMATION_STYLE_DYNAMIC_ISLAND
                ? "灵动岛" : "面容光环";
        Toast.makeText(this, "已切换为“" + name + "”，下次亮屏生效",
                Toast.LENGTH_SHORT).show();
    }

    private void updateAnimationStyleControls() {
        if (faceIdStyleButton == null || islandStyleButton == null
                || animationStyleSummary == null) {
            return;
        }
        int style = TemplateStore.getAnimationStyle(this);
        boolean island = style == TemplateStore.ANIMATION_STYLE_DYNAMIC_ISLAND;
        styleButtonState(faceIdStyleButton, !island);
        styleButtonState(islandStyleButton, island);
        animationStyleSummary.setText(island
                ? "灵动岛：加宽的深色胶囊、玻璃细边与流动扫描光，成功后自然收束。"
                : "面容光环：更开阔的双层表盘、环形光轨与柔和呼吸动效。" );
    }

    private void selectRecognitionProfile(int profile) {
        TemplateStore.setRecognitionProfile(this, profile);
        updateRecognitionProfileControls();
    }

    private void updateRecognitionProfileControls() {
        if (strictRecognitionButton == null || balancedRecognitionButton == null
                || comfortRecognitionButton == null || recognitionProfileSummary == null) {
            return;
        }
        int profile = TemplateStore.getRecognitionProfile(this);
        styleButtonState(strictRecognitionButton,
                profile == TemplateStore.RECOGNITION_PROFILE_STRICT);
        styleButtonState(balancedRecognitionButton,
                profile == TemplateStore.RECOGNITION_PROFILE_BALANCED);
        styleButtonState(comfortRecognitionButton,
                profile == TemplateStore.RECOGNITION_PROFILE_COMFORT);
        if (profile == TemplateStore.RECOGNITION_PROFILE_STRICT) {
            recognitionProfileSummary.setText("严格：身份距离上限最低，适合更重视误识别风险。" );
        } else if (profile == TemplateStore.RECOGNITION_PROFILE_COMFORT) {
            recognitionProfileSummary.setText("便捷：允许较小的环境变化，仍需通过多帧一致性。" );
        } else {
            recognitionProfileSummary.setText("均衡（推荐）：自动校准与多帧确认兼顾速度和容错。" );
        }
    }

    private void startCapture(String mode, int requestCode) {
        Intent intent = new Intent(this, FaceCaptureActivity.class);
        intent.putExtra(Constants.EXTRA_MODE, mode);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENROLL && resultCode == RESULT_OK) {
            Toast.makeText(this, "人脸录入完成", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQUEST_TEST && data != null) {
            float score = data.getFloatExtra(Constants.EXTRA_SCORE, Float.NaN);
            String result = resultCode == RESULT_OK ? "识别成功" : "识别失败";
            if (!Float.isNaN(score)) {
                result += String.format(Locale.CHINA, "，距离 %.3f", score);
            }
            new AlertDialog.Builder(this).setTitle("测试结果").setMessage(result)
                    .setPositiveButton("确定", null).show();
        }
        refreshState();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(rounded(Color.WHITE, 18));
        layout.setElevation(dp(1));
        return layout;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        return button;
    }

    private Button styleButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        return button;
    }

    private void styleButtonState(Button button, boolean selected) {
        button.setTextColor(selected ? Color.rgb(20, 92, 166) : Color.rgb(75, 86, 98));
        button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        button.setBackground(roundedStroke(
                selected ? Color.rgb(232, 244, 255) : Color.rgb(246, 248, 251),
                selected ? Color.rgb(78, 155, 230) : Color.rgb(216, 222, 229),
                14));
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundedStroke(int color, int strokeColor, int radiusDp) {
        GradientDrawable drawable = rounded(color, radiusDp);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

}

package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.camera.view.PreviewView;

/** Enrollment/test presentation only; silent lock-screen capture never constructs a preview. */
final class CaptureContentView extends FrameLayout {
    final PreviewView preview;
    private final TextView instruction;
    private final TextView detail;

    CaptureContentView(Context context, boolean enrollment, Runnable cancel) {
        super(context);
        setBackgroundColor(Color.BLACK);
        preview = new PreviewView(context);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        addView(preview, new LayoutParams(-1, -1));
        addView(new FaceGuideView(context), new LayoutParams(-1, -1));
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.setPadding(dp(24), dp(36), dp(24), dp(16));
        top.setBackgroundColor(Color.argb(145, 0, 0, 0));
        instruction = label(enrollment ? "录入人脸：请正视镜头" : "测试人脸识别", 22,
                Color.WHITE);
        top.addView(instruction, new LinearLayout.LayoutParams(-1, -2));
        detail = label("请将脸保持在取景框内", 14, Color.rgb(215, 225, 235));
        detail.setPadding(0, dp(8), 0, 0);
        top.addView(detail, new LinearLayout.LayoutParams(-1, -2));
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));
        Button button = new Button(context);
        button.setText("取消"); button.setTextSize(15); button.setAllCaps(false);
        button.setOnClickListener(v -> cancel.run());
        LayoutParams params = new LayoutParams(dp(148), dp(54),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = dp(36);
        addView(button, params);
    }

    void showInstruction(String value) { instruction.setText(value); }
    void showDetail(String value) { detail.setText(value); }
    private TextView label(String text, int size, int color) {
        TextView label = new TextView(getContext());
        label.setText(text); label.setTextSize(size); label.setTextColor(color);
        label.setGravity(Gravity.CENTER); return label;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class FaceGuideView extends View {
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private final android.graphics.PorterDuffXfermode clear = new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR);
        FaceGuideView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            shade.setColor(Color.argb(72, 0, 0, 0));
            border.setColor(Color.argb(230, 255, 255, 255));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(getResources().getDisplayMetrics().density * 3);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth() * .68f, height = width * 1.28f;
            float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
            oval.set(left, top, left + width, top + height);
            canvas.drawColor(shade.getColor());
            shade.setXfermode(clear); canvas.drawOval(oval, shade); shade.setXfermode(null);
            canvas.drawOval(oval, border);
        }
    }
}

package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.function.IntConsumer;

/** App-only gallery; thumbnails and live previews use the exact keyguard renderer. */
final class AnimationGallery {
    private AnimationGallery() { }

    static void show(Context context, int selected, IntConsumer onSelect) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout grid = Ui.column(context);
        grid.setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 8));
        scroll.addView(grid);
        AlertDialog dialog = new MaterialAlertDialogBuilder(context).setTitle("选择锁屏动画")
                .setView(scroll).setNegativeButton("返回", null).create();
        for (int row = 0; row < AnimationStyle.COUNT / 2; row++) {
            LinearLayout line = new LinearLayout(context);
            for (int column = 0; column < 2; column++) {
                int style = row * 2 + column;
                MaterialCardView card = new MaterialCardView(context);
                card.setCheckable(true);
                card.setChecked(style == selected);
                card.setClickable(true);
                card.setFocusable(true);
                card.setContentDescription(AnimationStyle.name(style));
                card.setRadius(Ui.dp(context, 18));
                card.setCardElevation(0);
                card.setMinimumHeight(Ui.dp(context, 120));
                card.setStrokeWidth(Ui.dp(context, style == selected ? 2 : 1));
                card.setStrokeColor(context.getColor(style == selected ? R.color.accent : R.color.outline));
                card.setCardBackgroundColor(context.getColor(
                        style == selected ? R.color.accent_surface : R.color.surface));
                LinearLayout tile = Ui.column(context);
                tile.setPadding(Ui.dp(context, 8), Ui.dp(context, 16),
                        Ui.dp(context, 8), Ui.dp(context, 16));
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                ImageView picture = new ImageView(context);
                picture.setImageDrawable(thumbnail(context, style));
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(picture, new LinearLayout.LayoutParams(-1, Ui.dp(context, 52)));
                TextView label = Ui.text(context, AnimationStyle.name(style), 16,
                        style == selected ? R.color.on_accent_surface : R.color.ink);
                label.setGravity(Gravity.CENTER);
                tile.addView(label, Ui.space(context, 8, 0));
                card.addView(tile, new FrameLayout.LayoutParams(-1, -2));
                card.setOnClickListener(view -> {
                    dialog.dismiss();
                    onSelect.accept(style);
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                if (column == 0) cell.setMarginEnd(Ui.dp(context, 12));
                line.addView(card, cell);
            }
            grid.addView(line, Ui.space(context, 0, 12));
        }
        dialog.show();
    }

    static void preview(Context context, int style) {
        LinearLayout content = Ui.column(context);
        content.setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), Ui.dp(context, 12));
        FrameLayout stage = new FrameLayout(context);
        stage.setBackground(Ui.rounded(context, R.color.capture_background, 24));
        UnlockFaceAnimationView animation = new UnlockFaceAnimationView(context, style);
        animation.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stage.addView(animation, new FrameLayout.LayoutParams(Ui.dp(context, 176),
                Ui.dp(context, 100), Gravity.CENTER));
        content.addView(stage, new LinearLayout.LayoutParams(-1, Ui.dp(context, 168)));
        MaterialButtonToggleGroup states = new MaterialButtonToggleGroup(context);
        states.setSingleSelection(true);
        states.setSelectionRequired(true);
        boolean largeText = context.getResources().getConfiguration().fontScale > 1.3f;
        if (largeText) states.setOrientation(LinearLayout.VERTICAL);
        String[] labels = {"识别中", "成功", "失败"};
        for (int state = 0; state < labels.length; state++) {
            int target = state;
            MaterialButton button = Ui.button(context, labels[state], false);
            button.setPaddingRelative(Ui.dp(context, 8), Ui.dp(context, 12),
                    Ui.dp(context, 8), Ui.dp(context, 12));
            states.addView(button, largeText ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            button.setOnClickListener(view -> {
                animation.restartSearching();
                if (target == 1) animation.showSuccess();
                else if (target == 2) animation.showFailure("");
                animation.setContentDescription("预览：" + labels[target]);
            });
            if (state == 0) states.check(button.getId());
        }
        content.addView(states, Ui.space(context, 20, 0));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        new MaterialAlertDialogBuilder(context).setTitle(AnimationStyle.name(style) + "预览")
                .setView(scroll).setPositiveButton("完成", null).show();
    }

    private static BitmapDrawable thumbnail(Context context, int style) {
        // Unattached views never start an animator; opening the gallery runs no background loops.
        UnlockFaceAnimationView view = new UnlockFaceAnimationView(context, style);
        int width = Ui.dp(context, 176), height = Ui.dp(context, 88);
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(Ui.dp(context, 104), Ui.dp(context, 52), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(context.getResources().getDisplayMetrics().densityDpi);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(bitmap.getWidth() / (float) width, bitmap.getHeight() / (float) height);
        view.draw(canvas);
        return new BitmapDrawable(context.getResources(), bitmap);
    }
}

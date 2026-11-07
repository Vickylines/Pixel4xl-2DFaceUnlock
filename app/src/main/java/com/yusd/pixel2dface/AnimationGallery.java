package com.yusd.pixel2dface;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
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

/** App-only chooser; the live surface uses the renderer injected into the native keyguard. */
final class AnimationGallery {
    private AnimationGallery() { }

    static void show(Context context, int selected, IntConsumer onSelect) {
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = Ui.column(context);
        content.setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), Ui.dp(context, 8));
        scroll.addView(content);
        Preview preview = new Preview(context, selected);
        content.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(context, 120)));
        MaterialButtonToggleGroup states = stateControls(context, preview);
        content.addView(states, Ui.space(context, 12, 18));
        MaterialCardView[] cards = new MaterialCardView[AnimationStyle.COUNT];
        TextView[] labels = new TextView[AnimationStyle.COUNT];
        int[] pending = {selected};
        int columns = context.getResources().getConfiguration().fontScale > 1.3f ? 2 : 3;
        for (int row = 0; row < AnimationStyle.COUNT / columns; row++) {
            LinearLayout line = new LinearLayout(context);
            for (int column = 0; column < columns; column++) {
                int style = row * columns + column;
                MaterialCardView card = new MaterialCardView(context);
                cards[style] = card;
                card.setCheckable(true);
                card.setClickable(true);
                card.setFocusable(true);
                card.setContentDescription(AnimationStyle.name(style));
                card.setRadius(Ui.dp(context, 16));
                card.setCardElevation(0);
                card.setCheckedIconSize(Ui.dp(context, 16));
                card.setCheckedIconMargin(Ui.dp(context, 5));
                LinearLayout tile = Ui.column(context);
                tile.setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10));
                tile.setGravity(Gravity.CENTER_HORIZONTAL);
                tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                ImageView picture = new ImageView(context);
                picture.setBackground(Ui.rounded(context, R.color.capture_background, 12));
                picture.setImageDrawable(thumbnail(context, style));
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                tile.addView(picture, new LinearLayout.LayoutParams(-1, Ui.dp(context, 44)));
                TextView label = Ui.text(context, AnimationStyle.name(style), 15, R.color.ink);
                labels[style] = label;
                label.setGravity(Gravity.CENTER);
                tile.addView(label, Ui.space(context, 6, 0));
                card.addView(tile, new FrameLayout.LayoutParams(-1, -2));
                updateSelection(context, card, label, style == selected);
                card.setOnClickListener(view -> {
                    if (pending[0] == style) return;
                    updateSelection(context, cards[pending[0]], labels[pending[0]], false);
                    pending[0] = style;
                    updateSelection(context, card, label, true);
                    preview.choose(style);
                    states.check(states.getChildAt(0).getId());
                    scroll.smoothScrollTo(0, 0);
                });
                LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1);
                if (column < columns - 1) cell.setMarginEnd(Ui.dp(context, 8));
                line.addView(card, cell);
            }
            content.addView(line, Ui.space(context, 0, 10));
        }
        new MaterialAlertDialogBuilder(context).setTitle("锁屏动画").setView(scroll)
                .setNegativeButton("返回", null)
                .setPositiveButton("使用此动画", (dialog, which) -> onSelect.accept(pending[0])).show();
    }

    private static void updateSelection(Context context, MaterialCardView card, TextView label, boolean selected) {
        card.setChecked(selected);
        card.setStrokeWidth(Ui.dp(context, selected ? 2 : 1));
        card.setStrokeColor(context.getColor(selected ? R.color.accent : R.color.outline));
        card.setCardBackgroundColor(context.getColor(selected ? R.color.accent_surface : R.color.surface));
        label.setTextColor(context.getColor(selected ? R.color.on_accent_surface : R.color.ink));
    }

    static void preview(Context context, int style) {
        LinearLayout content = Ui.column(context);
        content.setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), Ui.dp(context, 12));
        Preview stage = new Preview(context, style);
        content.addView(stage, new LinearLayout.LayoutParams(-1, Ui.dp(context, 152)));
        content.addView(stateControls(context, stage), Ui.space(context, 16, 0));
        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);
        new MaterialAlertDialogBuilder(context).setTitle(AnimationStyle.name(style))
                .setView(scroll).setPositiveButton("完成", null).show();
    }

    private static MaterialButtonToggleGroup stateControls(Context context, Preview preview) {
        MaterialButtonToggleGroup states = new MaterialButtonToggleGroup(context);
        states.setSingleSelection(true);
        states.setSelectionRequired(true);
        boolean largeText = context.getResources().getConfiguration().fontScale > 1.3f;
        if (largeText) states.setOrientation(LinearLayout.VERTICAL);
        String[] labels = {"识别中", "成功", "失败"};
        for (int state = 0; state < labels.length; state++) {
            int target = state;
            MaterialButton button = Ui.button(context, labels[state], false);
            button.setPaddingRelative(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10));
            states.addView(button, largeText ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1));
            button.setOnClickListener(view -> preview.state(target));
            if (state == 0) states.check(button.getId());
        }
        return states;
    }

    private static BitmapDrawable thumbnail(Context context, int style) {
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

    private static final class Preview extends FrameLayout {
        private UnlockFaceAnimationView active, outgoing;
        private int style, state;
        Preview(Context context, int style) {
            super(context);
            setBackground(Ui.rounded(context, R.color.capture_background, 22));
            this.style = style;
            replace();
        }
        void choose(int chosen) {
            style = chosen;
            state = 0;
            replace();
        }
        void state(int target) {
            if (state != 0 || target == 0) replace();
            state = target;
            if (target == 1) active.showSuccess();
            else if (target == 2) active.showFailure("");
            active.setContentDescription(target == 0 ? "识别中" : target == 1 ? "识别成功" : "识别失败");
        }
        private void replace() {
            if (outgoing != null) {
                outgoing.animate().cancel();
                removeView(outgoing);
            }
            outgoing = active;
            active = new UnlockFaceAnimationView(getContext(), style);
            active.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addView(active, new FrameLayout.LayoutParams(Ui.dp(getContext(), 176), Ui.dp(getContext(), 88), Gravity.CENTER));
            if (outgoing != null) {
                UnlockFaceAnimationView old = outgoing;
                old.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                old.animate().cancel();
                old.animate().alpha(0).setDuration(ValueAnimator.areAnimatorsEnabled() ? 160 : 0)
                        .setInterpolator(new PathInterpolator(.23f, 1f, .32f, 1f))
                        .withEndAction(() -> { removeView(old); if (outgoing == old) outgoing = null; }).start();
            }
        }
    }
}

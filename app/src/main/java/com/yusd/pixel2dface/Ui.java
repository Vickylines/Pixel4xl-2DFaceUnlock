package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;

/** Native controls and spacing for app screens; never loaded into SystemUI. */
final class Ui {
    private Ui() { }
    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(context.getColor(color));
        view.setLineSpacing(0, 1.12f);
        return view;
    }
    static TextView heading(Context context, String value, int size) {
        TextView view = text(context, value, size, R.color.ink);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAccessibilityHeading(true);
        return view;
    }
    static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    static LinearLayout.LayoutParams space(Context context, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(context, top), 0, dp(context, bottom));
        return params;
    }
    static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(context.getColor(color));
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }
    static MaterialButton button(Context context, String label, boolean filled) {
        MaterialButton button = new MaterialButton(context, null, filled
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 56));
        button.setMinimumWidth(0);
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(context, 18));
        button.setPaddingRelative(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        return button;
    }
}

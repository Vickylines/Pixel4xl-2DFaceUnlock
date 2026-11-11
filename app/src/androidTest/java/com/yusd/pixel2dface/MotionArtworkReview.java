package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.io.FileOutputStream;

/** Native artwork export, reachable only through the emulator-only review runner. */
final class MotionArtworkReview {
    static void export(Context context) throws Exception {
        File folder = context.getExternalFilesDir("art-review");
        LockscreenStyleRenderer[] renderers = new LockscreenStyleRenderer[4];
        for (int i = 0; i < renderers.length; i++) renderers[i] = new LockscreenStyleRenderer();
        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Bitmap image = Bitmap.createBitmap(1056,720,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        for (int frame = 0; frame < 180; frame++) {
            float seconds = frame / 30f;
            int state = seconds < 2.4f || (seconds >= 3.6f && seconds < 4.8f) ? 0
                    : seconds < 3.6f ? 1 : 2;
            float phase = state == 1 ? 2.4f / 2.8f : state == 2 ? 1.2f / 2.8f
                    : (seconds >= 3.6f ? seconds - 3.6f : seconds) / 2.8f;
            float progress = state == 0 ? 0 : Math.min(1,
                    (seconds - (state == 1 ? 2.4f : 4.8f)) / (state == 1 ? .52f : .42f));
            canvas.drawColor(0xff10141f);
            label.setTextSize(32); label.setColor(0xffefbb66);
            canvas.drawText(context.getString(R.string.app_name),40,51,label);
            label.setTextSize(23); label.setColor(0xffdfe5ee);
            canvas.drawText(state == 0 ? "识别中" : state == 1 ? "成功" : "失败",925,49,label);
            for (int i = 0; i < renderers.length; i++) {
                float x = i % 2 * 528, y = i / 2 * 320 + 72;
                int save = canvas.save();
                canvas.translate(x + 264,y + 128);
                canvas.scale(3,3);
                renderers[i].draw(canvas,i + AnimationStyle.GLOW,state,phase,progress);
                canvas.restoreToCount(save);
                label.setTextSize(24); label.setColor(0xffe3e8f0);
                label.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(AnimationStyle.name(i + AnimationStyle.GLOW),x + 264,y + 287,label);
                label.setTextAlign(Paint.Align.LEFT);
            }
            write(image,new File(folder,String.format(java.util.Locale.ROOT,"frame-%03d.png",frame)));
        }
        image.recycle();
        Drawable icon = context.getApplicationInfo().loadIcon(context.getPackageManager());
        if (!(icon instanceof AdaptiveIconDrawable)) throw new AssertionError("Launcher icon is not adaptive");
        AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) icon;
        if (adaptive.getMonochrome() == null) throw new AssertionError("Themed icon is missing");
        Bitmap icons = Bitmap.createBitmap(768,384,Bitmap.Config.ARGB_8888);
        Canvas iconCanvas = new Canvas(icons);
        iconCanvas.drawColor(0xfff6f6f3);
        adaptive.setBounds(32,32,352,352);
        adaptive.draw(iconCanvas);
        AdaptiveIconDrawable themed = new AdaptiveIconDrawable(
                new android.graphics.drawable.ColorDrawable(0xfff5ddb5),adaptive.getMonochrome());
        themed.getForeground().setTint(Color.rgb(62,48,29));
        themed.setBounds(416,32,736,352);
        themed.draw(iconCanvas);
        write(icons,new File(folder,"adaptive-icons.png"));
        icons.recycle();
    }
    private static void write(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))
                throw new IllegalStateException("PNG export failed");
        }
    }
}

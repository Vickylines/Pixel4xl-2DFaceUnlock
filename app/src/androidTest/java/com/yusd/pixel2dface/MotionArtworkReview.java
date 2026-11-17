package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SEARCHING;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

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
import java.lang.reflect.Field;

/** Native artwork export, reachable only through the emulator-only review runner. */
final class MotionArtworkReview {
    static int export(Context context) throws Exception {
        File folder = context.getExternalFilesDir("art-review");
        UnlockFaceAnimationView focus = new UnlockFaceAnimationView(context, AnimationStyle.FOCUS);
        UnlockFaceAnimationView faceRing = new UnlockFaceAnimationView(context, AnimationStyle.FACE);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(176 * density);
        int height = Math.round(88 * density);
        focus.layout(0, 0, width, height);
        faceRing.layout(0, 0, width, height);
        Field scanPhase = UnlockFaceAnimationView.class.getDeclaredField("phase");
        scanPhase.setAccessible(true);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Bitmap image = Bitmap.createBitmap(528,392,Bitmap.Config.ARGB_8888);
        Bitmap reference = Bitmap.createBitmap(528,392,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Canvas referenceCanvas = new Canvas(reference);
        int previous = SEARCHING;
        int sharedFrames = 0;
        for (int frame = 0; frame < 360; frame++) {
            float seconds = frame / 60f;
            int state = seconds < 2.4f || (seconds >= 3.6f && seconds < 4.8f) ? SEARCHING
                    : seconds < 3.6f ? SUCCESS : FAILURE;
            float scanSeconds = seconds >= 3.6f ? seconds - 3.6f : seconds;
            float phase = (scanSeconds * 1000 / UnlockFaceAnimationView.SCAN_CYCLE_MS) % 1f;
            scanPhase.setFloat(focus, phase);
            scanPhase.setFloat(faceRing, phase);
            float progress = state == SEARCHING ? 0 : Math.min(1,
                    (seconds - (state == SUCCESS ? 2.4f : 4.8f)) * 1000
                            / (state == SUCCESS ? UnlockFaceAnimationView.SUCCESS_MORPH_MS
                            : UnlockFaceAnimationView.FAILURE_MORPH_MS));

            boolean startingOutcome = state != SEARCHING && state != previous;
            if (startingOutcome) {
                reference.eraseColor(Color.TRANSPARENT);
                draw(referenceCanvas, focus, density, 0);
                if (state == SUCCESS) {
                    focus.showSuccess();
                    faceRing.showSuccess();
                } else {
                    focus.showFailure("");
                    faceRing.showFailure("");
                }
            } else if (state != previous) {
                focus.restartSearching();
                faceRing.restartSearching();
            }
            previous = state;

            image.eraseColor(Color.TRANSPARENT);
            draw(canvas, focus, density, progress);
            if (startingOutcome && !image.sameAs(reference)) {
                throw new IllegalStateException("Focus jumped on its first outcome frame");
            }
            if (state != SEARCHING && progress >= UnlockFaceAnimationView.FOCUS_HANDOFF_END) {
                reference.eraseColor(Color.TRANSPARENT);
                draw(referenceCanvas, faceRing, density, progress);
                if (!image.sameAs(reference)) {
                    throw new IllegalStateException("Focus differs from Face Ring at frame " + frame);
                }
                sharedFrames++;
            }

            label.setTextSize(32); label.setColor(0xffefbb66);
            canvas.drawText(context.getString(R.string.app_name),40,51,label);
            label.setTextSize(23); label.setColor(0xffdfe5ee);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(state == SEARCHING ? "识别中" : state == SUCCESS ? "成功" : "失败",488,49,label);
            label.setTextSize(24); label.setColor(0xffe3e8f0);
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(AnimationStyle.name(AnimationStyle.FOCUS),264,344,label);
            label.setTextAlign(Paint.Align.LEFT);
            write(image,new File(folder,String.format(java.util.Locale.ROOT,"frame-%03d.png",frame)));
        }
        image.recycle();
        reference.recycle();
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
        return sharedFrames;
    }

    private static void draw(Canvas canvas, UnlockFaceAnimationView view, float density,
            float progress) {
        int save = canvas.save();
        canvas.translate(264, 200);
        canvas.scale(3 / density, 3 / density);
        canvas.translate(-view.getWidth() / 2f, -view.getHeight() / 2f);
        view.drawFrame(canvas, progress);
        canvas.restoreToCount(save);
    }
    private static void write(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))
                throw new IllegalStateException("PNG export failed");
        }
    }
}

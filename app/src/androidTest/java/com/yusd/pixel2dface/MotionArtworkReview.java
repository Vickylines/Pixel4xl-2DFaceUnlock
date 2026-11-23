package com.yusd.pixel2dface;

import static com.yusd.pixel2dface.UnlockFaceAnimationView.FAILURE;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SEARCHING;
import static com.yusd.pixel2dface.UnlockFaceAnimationView.SUCCESS;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Typeface;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Native artwork export, reachable only through the emulator-only review runner. */
final class MotionArtworkReview {
    static void export(Context context, int style, boolean quickScan, boolean hardwareFrames,
            boolean wideFrames) throws Exception {
        File folder = context.getExternalFilesDir(hardwareFrames ? "art-review-100" : "art-review");
        UnlockFaceAnimationView view = new UnlockFaceAnimationView(context, style);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(176 * density);
        int height = Math.round(88 * density);
        view.layout(0, 0, width, height);
        Field scanPhase = UnlockFaceAnimationView.class.getDeclaredField("phase");
        scanPhase.setAccessible(true);

        Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        Bitmap image = Bitmap.createBitmap(528,392,Bitmap.Config.ARGB_8888);
        Bitmap reference = Bitmap.createBitmap(528,392,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        Canvas referenceCanvas = new Canvas(reference);
        File hardwareFolder = hardwareFrames
                ? context.getExternalFilesDir(wideFrames ? "art-hardware-wide" : "art-hardware") : null;
        int hardwareWidth = wideFrames ? 1056 : 384, hardwareHeight = wideFrames ? 528 : 384;
        // Check the handoff at the front, side, back and just before the loop wraps.
        for (float phase : new float[]{0, .13f, .178f, .225f, .30f, .34f, .61f, .99f}) {
            for (int result : new int[]{SUCCESS, FAILURE}) {
                view.restartSearching();
                scanPhase.setFloat(view, phase);
                reference.eraseColor(Color.TRANSPARENT);
                draw(referenceCanvas, view, density, 0);
                if (result == SUCCESS) view.showSuccess();
                else view.showFailure(true);
                image.eraseColor(Color.TRANSPARENT);
                draw(canvas, view, density, 0);
                if (!image.sameAs(reference)) throw new AssertionError("Outcome jumped: " + style + "/" + phase);
                if (style == AnimationStyle.FOCUS) {
                    image.eraseColor(Color.TRANSPARENT);
                    draw(canvas, view, density, 1);
                    verifyFocusResult(image, result == FAILURE);
                    if (result == SUCCESS) verifyCheckWriting(image, canvas, view, density, phase);
                }
            }
        }
        // Start a result during the turn as well as after a longer scan.
        float successAt = quickScan ? .40f : 2.4f;
        float retryAt = quickScan ? 1.85f : 3.6f;
        float failureAt = quickScan ? 2.25f : 4.8f;
        view.restartSearching();
        int previous = SEARCHING;
        // 100 Hz gives both a 50 fps preview and its half-speed replay exact native poses.
        int sampleRate = hardwareFrames ? 100 : 60;
        int hardwareFrameCount = Math.round((wideFrames ? failureAt + 1.8f : 3.4f) * sampleRate);
        for (int frame = 0; frame < Math.max(6 * sampleRate, hardwareFrameCount); frame++) {
            float seconds = frame / (float) sampleRate;
            int state = seconds < successAt || (seconds >= retryAt && seconds < failureAt) ? SEARCHING
                    : seconds < retryAt ? SUCCESS : FAILURE;
            float scanSeconds = seconds >= retryAt ? seconds - retryAt : seconds;
            float phase = (scanSeconds * 1000 / view.scanCycleMs()) % 1f;
            scanPhase.setFloat(view, phase);

            boolean startingOutcome = state != SEARCHING && state != previous;
            if (startingOutcome) {
                reference.eraseColor(Color.TRANSPARENT);
                draw(referenceCanvas, view, density, 0);
                if (state == SUCCESS) {
                    view.showSuccess();
                } else {
                    view.showFailure(true);
                }
            } else if (state != previous) {
                view.restartSearching();
            }
            previous = state;

            float progress = state == SEARCHING ? 0 : Math.min(1,
                    (seconds - (state == SUCCESS ? successAt : failureAt)) * 1000 / view.resultDuration());
            image.eraseColor(Color.TRANSPARENT);
            draw(canvas, view, density, progress);
            if (startingOutcome && !image.sameAs(reference)) {
                throw new IllegalStateException("First outcome frame did not match the scan: " + style);
            }
            if (style == AnimationStyle.FOCUS && state != SEARCHING && progress == 1)
                verifyFocusResult(image, state == FAILURE);
            if (hardwareFolder != null && frame < hardwareFrameCount) {
                // Also exercise HWUI. Translucent vertex artwork can composite differently
                // when drawn into a bitmap Canvas and when presented by the live view.
                Picture picture = new Picture();
                Canvas recording = picture.beginRecording(hardwareWidth, hardwareHeight);
                recording.translate(hardwareWidth / 2f, hardwareHeight / 2f);
                recording.scale(6 / density, 6 / density);
                recording.translate(-view.getWidth() / 2f, -view.getHeight() / 2f);
                view.drawFrame(recording, progress);
                picture.endRecording();
                Bitmap hardware = Bitmap.createBitmap(picture);
                Bitmap pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
                write(pixels, new File(hardwareFolder, String.format(java.util.Locale.ROOT, "frame-%03d.png", frame)));
                pixels.recycle();
                hardware.recycle();
            }

            label.setTextSize(32); label.setColor(0xffefbb66);
            canvas.drawText(context.getString(R.string.app_name),40,51,label);
            label.setTextSize(23); label.setColor(0xffdfe5ee);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(state == SEARCHING ? "识别中" : state == SUCCESS ? "成功" : "失败",488,49,label);
            label.setTextSize(24); label.setColor(0xffe3e8f0);
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(AnimationStyle.name(style),264,344,label);
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
    }

    private static void verifyFocusResult(Bitmap image, boolean failed) {
        if (failed) {
            verifyClosedLockAndHint(image);
            return;
        }
        if (!greenInk(image,-6,4) || !greenInk(image,4.7f,0))
            throw new AssertionError("Missing Focus result strokes: " + failed);
        if (!greenInk(image,23.2f,0) || !greenInk(image,0,-23.2f))
            throw new AssertionError("The four Focus corners did not close into the result ring");
        if (Color.green(image.getPixel(264,234)) > 20 || Color.alpha(image.getPixel(264,104)) < 250
                || Color.alpha(image.getPixel(264,65)) != 0)
            throw new AssertionError("Success requires a clean black tile behind its checkmark");
    }

    private static void verifyClosedLockAndHint(Bitmap image) {
        int left = 0;
        while (left < 264 && !brightWhite(image.getPixel(left, 209))) left++;
        int right = left;
        while (right < 264 && brightWhite(image.getPixel(right, 209))) right++;
        int centre = (left + right) / 2;
        if (right - left < 33 || right - left > 44 || !brightWhite(image.getPixel(centre, 176))
                || Color.green(image.getPixel(centre, 188)) > 35)
            throw new AssertionError("The smaller lock must show a white body and a closed shackle");
        int captionPixels = 0;
        for (int x = right + 8; x < image.getWidth() - 5; x += 4) {
            for (int y = 178; y < 223; y += 4) {
                if (brightWhite(image.getPixel(x, y))) captionPixels++;
            }
        }
        if (captionPixels < 35 || Color.alpha(image.getPixel(264, 122)) > 15)
            throw new AssertionError("Missing swipe hint or a leftover scanning ring");
    }

    private static boolean brightWhite(int p) {
        return Color.alpha(p) > 160 && Color.red(p) > 200
                && Color.green(p) > 200 && Color.blue(p) > 200;
    }

    private static void verifyCheckWriting(Bitmap image, Canvas canvas, UnlockFaceAnimationView view,
            float density, float phase) {
        long landing = FocusMotion.remainingTurnMs(phase);
        image.eraseColor(Color.TRANSPARENT);
        draw(canvas, view, density, (landing + 160f) / view.resultDuration());
        if (!greenInk(image, -6, 4) || greenInk(image, 2.4f, 2))
            throw new AssertionError("The short arm must lead the rising stroke");
        image.eraseColor(Color.TRANSPARENT);
        draw(canvas, view, density, (landing + 270f) / view.resultDuration());
        if (!greenInk(image, -6, 4) || !greenInk(image, 2.4f, 2) || greenInk(image, 11.4f, -8.3f))
            throw new AssertionError("The long arm must travel through the middle before reaching its tip");
    }

    private static boolean greenInk(Bitmap image, float x, float y) {
        int pixel = image.getPixel(Math.round(264 + 2.625f * x), Math.round(200 + 2.625f * y));
        return Color.alpha(pixel) > 160 && Color.green(pixel) - Color.red(pixel) > 35;
    }

    static void verifyStillStates(Context context) throws Exception {
        if (ValueAnimator.areAnimatorsEnabled()) throw new AssertionError("Disable animator duration scale first");
        Field phase = UnlockFaceAnimationView.class.getDeclaredField("phase");
        phase.setAccessible(true);
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(176 * density), height = Math.round(88 * density);
        Bitmap scan = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap success = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < AnimationStyle.COUNT; i++) {
            UnlockFaceAnimationView view = new UnlockFaceAnimationView(context, AnimationStyle.at(i));
            view.layout(0, 0, width, height);
            scan.eraseColor(Color.TRANSPARENT);
            view.draw(new Canvas(scan));
            phase.setFloat(view, .63f);
            actual.eraseColor(Color.TRANSPARENT);
            view.draw(new Canvas(actual));
            if (!scan.sameAs(actual)) throw new AssertionError("Disabled scan moved: " + i);
            for (int state : new int[]{SUCCESS, FAILURE}) {
                view.restartSearching();
                if (state == SUCCESS) view.showSuccess();
                else view.showFailure(true);
                Bitmap result = state == SUCCESS ? success : actual;
                result.eraseColor(Color.TRANSPARENT);
                view.draw(new Canvas(result));
                expected.eraseColor(Color.TRANSPARENT);
                view.drawFrame(new Canvas(expected), 1);
                if (!expected.sameAs(result) || scan.sameAs(result))
                    throw new AssertionError("Disabled result did not appear immediately: " + i);
            }
            if (success.sameAs(actual)) throw new AssertionError("Results look identical: " + i);
            if (AnimationStyle.at(i) == AnimationStyle.ISLAND
                    && Color.alpha(success.getPixel(width / 2, height / 2 + Math.round(25 * density))) < 240)
                throw new AssertionError("Reduced motion must retain the expanded confirmation");
        }
        scan.recycle();
        actual.recycle();
        expected.recycle();
        success.recycle();
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

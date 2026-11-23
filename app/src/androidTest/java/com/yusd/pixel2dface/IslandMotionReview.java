package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.Rect;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Locale;

/** Samples the same native drawing code that handles a failure, a user swipe, and a new result. */
final class IslandMotionReview {
    private static final int CELL = 440, HEIGHT = 220;
    private static final int FAIL = 1240, PULL = 2700, RELEASE = 2860, SUCCESS = 4100;
    private IslandMotionReview() {}

    static void export(Context context, boolean directSuccess, boolean earlySuccess) throws Exception {
        File dir = context.getExternalFilesDir(earlySuccess ? "island-early" : directSuccess ? "island-success" : "island-review");
        int successAt = earlySuccess ? 140 : directSuccess ? 920 : SUCCESS;
        int endAt = earlySuccess ? 2720 : directSuccess ? 4120 : 7060;
        float density = context.getResources().getDisplayMetrics().density;
        UnlockFaceAnimationView[] views = new UnlockFaceAnimationView[AnimationStyle.COUNT];
        Field phase = UnlockFaceAnimationView.class.getDeclaredField("phase");
        Field started = UnlockFaceAnimationView.class.getDeclaredField("stateStartedAt");
        phase.setAccessible(true);
        started.setAccessible(true);
        verifyReleaseHandoff(context, density, phase, started);
        for (int k=0;k<views.length;k++) {
            views[k] = new UnlockFaceAnimationView(context,AnimationStyle.at(k));
            views[k].layout(0,0,Math.round(176*density),Math.round(88*density));
        }
        int compactHeight = 0, expandedHeight = 0;
        int compactTop = 0, expandedTop = 0, compactWidth = 0, expandedWidth = 0;
        int[] islandPixels = new int[CELL * HEIGHT];
        UnlockFaceAnimationView island = views[AnimationStyle.positionOf(AnimationStyle.ISLAND)];
        for (int frame = 0; frame * 20 <= endAt; frame++) {
            int ms = frame*20;
            Picture picture = new Picture();
            Canvas draw = picture.beginRecording(CELL*views.length,HEIGHT);
            for (int k=0;k<views.length;k++) {
                UnlockFaceAnimationView v = views[k];
                int age = !directSuccess && ms >= RELEASE ? ms - RELEASE : ms;
                phase.setFloat(v,(age/(float)v.scanCycleMs())%1);
                if (!directSuccess) {
                    if (ms == FAIL) v.showFailure(true);
                    if (ms >= PULL && ms <= RELEASE) v.pullRetry((ms - PULL) / (float) (RELEASE - PULL));
                    if (ms == RELEASE) {
                        started.setLong(v, SystemClock.elapsedRealtime() - (RELEASE - FAIL));
                        v.retrySearching(false);
                    }
                }
                if (ms == successAt) v.showSuccessAt(age);
                float progress = ms >= successAt ? Math.min(1, (ms - successAt) / (float) v.resultDuration())
                        : !directSuccess && ms >= FAIL && ms < RELEASE
                        ? Math.min(1, (ms - FAIL) / (float) v.resultDuration()) : 0;
                int save = draw.save();
                draw.translate(k*CELL,0);
                draw.scale(CELL/(float)v.getWidth(),HEIGHT/(float)v.getHeight());
                v.drawFrameAt(draw,progress,age);
                draw.restoreToCount(save);
            }
            picture.endRecording();
            Bitmap hw = Bitmap.createBitmap(picture);
            Bitmap sample = hw.copy(Bitmap.Config.ARGB_8888,false);
            if (ms >= successAt && ms - successAt >= island.resultDuration()
                    - IslandMotion.CHECK_HOLD_MS - IslandMotion.CLOSE_MS) {
                verifyCenteredConfirmation(sample, islandPixels, ms - successAt >= island.resultDuration());
            }
            if (ms==0 || ms==520) {
                int min=HEIGHT,max=-1;
                for (int y=0;y<HEIGHT;y++)
                    if (Color.alpha(sample.getPixel(CELL+CELL/2,y))>244) {min=Math.min(min,y);max=y;}
                int left = CELL, right = -1;
                for (int x = 0; x < CELL; x++) {
                    if (Color.alpha(sample.getPixel(CELL + x, (min + max) / 2)) > 244) {
                        left = Math.min(left, x);
                        right = x;
                    }
                }
                if (ms == 0) {
                    compactTop = min;
                    compactHeight = max - min + 1;
                    compactWidth = right - left + 1;
                } else {
                    expandedTop = min;
                    expandedHeight = max - min + 1;
                    expandedWidth = right - left + 1;
                }
            }
            try (FileOutputStream out = new FileOutputStream(new File(dir,String.format(Locale.ROOT,"frame-%03d.png",frame)))) {
                if (!sample.compress(Bitmap.CompressFormat.PNG,100,out)) throw new AssertionError("Frame write failed");
            }
            sample.recycle(); hw.recycle();
        }
        if (compactHeight<47 || compactHeight>56 || expandedHeight<195 || expandedHeight>210)
            throw new AssertionError("Island geometry did not expand from its top: "+compactHeight+" to "+expandedHeight);
        if (Math.abs(expandedTop - compactTop) > 5 || expandedWidth < compactWidth * 1.13f
                || expandedWidth > compactWidth * 1.19f)
            throw new AssertionError("Unexpected island anchor or width: " + compactTop + "," + compactWidth
                    + " to " + expandedTop + "," + expandedWidth);
    }

    private static void verifyReleaseHandoff(Context context, float density, Field phase, Field started)
            throws IllegalAccessException {
        int width = Math.round(176 * density), height = Math.round(88 * density);
        Bitmap before = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap after = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < AnimationStyle.COUNT; i++) {
            for (boolean success : new boolean[]{false, true}) {
                UnlockFaceAnimationView view = new UnlockFaceAnimationView(context, AnimationStyle.at(i));
                view.layout(0, 0, width, height);
                view.showFailure(true);
                started.setLong(view, SystemClock.elapsedRealtime() - view.resultDuration());
                view.pullRetry(.85f);
                view.retrySearching(false);
                int age = 85;
                phase.setFloat(view, age / (float) view.scanCycleMs());
                before.eraseColor(Color.TRANSPARENT);
                after.eraseColor(Color.TRANSPARENT);
                view.drawFrameAt(new Canvas(before), 0, age);
                if (success) view.showSuccess(); else view.showFailure(true);
                view.drawFrameAt(new Canvas(after), 0, age);
                if (!before.sameAs(after)) throw new AssertionError("Capsule jumped during swipe release: " + i);
            }
        }
        before.recycle();
        after.recycle();
    }

    private static void verifyCenteredConfirmation(Bitmap frame, int[] pixels, boolean compact) {
        frame.getPixels(pixels, 0, CELL, CELL, 0, CELL, HEIGHT);
        int top = 0, bottom = HEIGHT - 1;
        while (top <= bottom && Color.alpha(pixels[top * CELL + CELL / 2]) < 245) top++;
        while (bottom > top && Color.alpha(pixels[bottom * CELL + CELL / 2]) < 245) bottom--;
        if (top >= bottom) throw new AssertionError("Missing island behind the result");
        int yMid = (top + bottom) / 2;
        int left = 0, right = CELL - 1;
        while (left < right && Color.alpha(pixels[yMid * CELL + left]) < 245) left++;
        while (right > left && Color.alpha(pixels[yMid * CELL + right]) < 245) right--;
        Rect panel = new Rect(left, top, right + 1, bottom + 1);
        Rect rim = new Rect(CELL, HEIGHT, 0, 0);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (isGreen(pixels[y * CELL + x])) rim.union(x, y, x + 1, y + 1);
            }
        }
        checkCenter("ring", rim, panel);
        // Inside 78% of the ring radius only the written check can contribute green pixels.
        Rect check = new Rect(CELL, HEIGHT, 0, 0);
        float inner = rim.width() * .39f;
        int ink = 0;
        for (int y = rim.top; y < rim.bottom; y++) {
            for (int x = rim.left; x < rim.right; x++) {
                float dx = x + .5f - rim.exactCenterX(), dy = y + .5f - rim.exactCenterY();
                if (dx * dx + dy * dy <= inner * inner && isGreen(pixels[y * CELL + x])) {
                    check.union(x, y, x + 1, y + 1);
                    ink++;
                }
            }
        }
        checkCenter("check", check, panel);
        if (compact && (ink < 65 || rim.height() < .665f * panel.height()))
            throw new AssertionError("Compact confirmation is too thin: " + ink + " px, " + rim);
    }

    private static boolean isGreen(int pixel) {
        return Color.alpha(pixel) > 192 && Color.green(pixel) > 116
                && Color.green(pixel) - Color.red(pixel) > 42 && Color.green(pixel) - Color.blue(pixel) > 32;
    }

    private static void checkCenter(String part, Rect ink, Rect panel) {
        if (ink.isEmpty() || Math.abs(ink.exactCenterX() - panel.exactCenterX()) > 1.5f
                || Math.abs(ink.exactCenterY() - panel.exactCenterY()) > 1.5f)
            throw new AssertionError(part + " left the center: " + ink + " inside " + panel);
    }
}

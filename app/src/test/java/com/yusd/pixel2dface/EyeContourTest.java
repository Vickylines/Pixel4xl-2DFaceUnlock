package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import android.graphics.PointF;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class EyeContourTest {
    @Test public void eyeOpeningDoesNotGrowWhenTheHeadTilts() {
        for (int roll : new int[]{-25, -15, 0, 15, 25}) {
            float ratio = ratio(.04f, roll);
            assertEquals("roll=" + roll, .04f, ratio, .00001f);
            assertEquals(PassiveEyeGate.Problem.CONTOUR_UNRELIABLE,
                    PassiveEyeGate.problem(.95f, .95f, ratio, ratio));
        }
    }

    @Test public void openEyesKeepTheSameMeasurementWhenRotatedOrMirrored() {
        for (int roll : new int[]{-25, 0, 25}) {
            List<PointF> points = contour(.25f, roll);
            PointF first = transform(-30, 0, roll), second = transform(30, 0, roll);
            assertEquals(.25f, FaceFrameExtractor.eyeContourRatio(points, first, second), .00001f);
            assertEquals(.25f, FaceFrameExtractor.eyeContourRatio(points, second, first), .00001f);
            assertTrue(PassiveEyeGate.areBothEyesOpen(.9f, .9f, ratio(.25f, roll), ratio(.25f, roll)));
        }
    }

    @Test public void missingOrDegenerateLandmarksCannotSupplyOpenEyeEvidence() {
        List<PointF> points = contour(.25f, 0);
        PointF eye = new PointF(100, 120);
        assertEquals(0, FaceFrameExtractor.eyeContourRatio(points, null, eye), 0);
        assertEquals(0, FaceFrameExtractor.eyeContourRatio(points, eye, eye), 0);
        assertEquals(0, FaceFrameExtractor.eyeContourRatio(points, eye, new PointF(Float.NaN, 0)), 0);
        points.set(0, new PointF(Float.POSITIVE_INFINITY, 120));
        assertEquals(0, FaceFrameExtractor.eyeContourRatio(points, eye, new PointF(160, 120)), 0);
    }

    private static float ratio(float opening, int roll) {
        return FaceFrameExtractor.eyeContourRatio(contour(opening, roll),
                transform(-30, 0, roll), transform(30, 0, roll));
    }

    private static List<PointF> contour(float opening, int roll) {
        List<PointF> points = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double angle = i * Math.PI / 8;
            points.add(transform((float) Math.cos(angle) * 10,
                    (float) Math.sin(angle) * 10 * opening, roll));
        }
        return points;
    }

    private static PointF transform(float x, float y, int roll) {
        double angle = Math.toRadians(roll);
        return new PointF((float) (100 + x * Math.cos(angle) - y * Math.sin(angle)),
                (float) (120 + x * Math.sin(angle) + y * Math.cos(angle)));
    }
}

package com.yusd.pixel2dface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.SystemClock;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceLandmark;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Native pipeline comparison using a public-domain fixture, never the device enrollment. */
final class CapturePipelineChecks {
    static void run(Context testContext, Bundle result) throws Exception {
        Bitmap source;
        try (InputStream stream = testContext.getAssets().open("astronaut.png")) {
            source = BitmapFactory.decodeStream(stream);
        }
        Bitmap crop = Bitmap.createBitmap(source, 120, 0, 240, 320);
        Bitmap portrait = Bitmap.createScaledBitmap(crop, 480, 640, false);
        crop.recycle(); source.recycle();
        InputImage image = InputImage.fromBitmap(portrait, 0);
        long[][] times = new long[2][24];
        try (FaceDetector population = FaceDetection.getClient(FaceDetectorFactory.populationOptions());
                FaceDetector detail = FaceDetection.getClient(FaceDetectorFactory.detailOptions());
                SingleFaceDetector joined = FaceDetectorFactory.create(Runnable::run, () -> false)) {
            await(joined.warmUp());
            for (int round = -4; round < 24; round++) {
                List<Face> sequential = null, parallel = null;
                for (int offset = 0; offset < 2; offset++) {
                    int lane = (round + 4 + offset) % 2;
                    long start = SystemClock.elapsedRealtimeNanos();
                    if (lane == 0) {
                        sequential = await(population.process(image));
                        if (sequential.size() == 1) sequential = await(detail.process(image));
                    } else {
                        parallel = await(joined.process(image));
                    }
                    if (round >= 0) times[lane][round] = SystemClock.elapsedRealtimeNanos() - start;
                }
                require(sequential.size() == 1 && parallel.size() == 1, "single face fixture");
                try { sameFace(sequential.get(0), parallel.get(0), false); }
                catch (AssertionError failure) { throw new AssertionError("comparison round " + round, failure); }
            }
            Face upright = await(joined.process(image)).get(0);
            require(upright.getAllContours().size() > 0, "native contour results");
            require(upright.getAllLandmarks().size() > 0, "native landmark results");
            int independentDirections = 0, maximumSearchFrames = 0;
            for (int correction : new int[]{0, 90, 180, 270}) {
                Matrix rotate = new Matrix(); rotate.postRotate((360 - correction) % 360);
                Bitmap raw = Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(),
                        portrait.getHeight(), rotate, false);
                for (int gravity : new int[]{0, 90, 180, 270}) {
                    DetectionOrientation direction = new DetectionOrientation();
                    boolean found = false;
                    for (int frame = 1; frame <= 4; frame++) {
                        int selected = direction.rotationFor(gravity);
                        List<Face> faces = await(joined.process(InputImage.fromBitmap(raw, selected)));
                        direction.observe(faces.size(), faces.size() == 1 ? faces.get(0).getHeadEulerAngleZ() : 0);
                        if (faces.size() == 1 && Math.abs(faces.get(0).getHeadEulerAngleZ()) <= 25) {
                            require(selected == correction, "face direction independent of gravity "
                                    + correction + "/" + gravity + " selected " + selected);
                            sameFace(upright, faces.get(0), true);
                            maximumSearchFrames = Math.max(maximumSearchFrames, frame);
                            found = true;
                            for (int stable = 0; stable < 5; stable++) {
                                require(direction.rotationFor(gravity) == correction, "stable capture direction");
                                direction.observe(1, faces.get(0).getHeadEulerAngleZ());
                            }
                            break;
                        }
                    }
                    require(found, "bedtime direction found " + correction + "/" + gravity);
                    independentDirections++;
                }
                if (raw != portrait) raw.recycle();
            }
            result.putInt("faceGravityCombinationsPassed", independentDirections);
            result.putInt("maximumDirectionSearchFrames", maximumSearchFrames);
            int orientations = 0;
            float maximumContourDelta = 0;
            for (int correction : new int[]{0, 90, 180, 270}) {
                Matrix rotate = new Matrix();
                rotate.postRotate((360 - correction) % 360);
                Bitmap raw = Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(),
                        portrait.getHeight(), rotate, false);
                List<Face> faces = await(joined.process(InputImage.fromBitmap(raw, correction)));
                require(faces.size() == 1, "detect rotated face " + correction);
                try { maximumContourDelta = Math.max(maximumContourDelta,
                        sameFace(upright, faces.get(0), true)); }
                catch (AssertionError failure) { throw new AssertionError("rotation " + correction, failure); }
                if (raw != portrait) raw.recycle();
                orientations++;
            }
            // Contours select one prominent face, so the independent population result
            // must continue to reject a two-person scene, in every camera orientation.
            Bitmap crowd = Bitmap.createBitmap(960, 640, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(crowd);
            canvas.drawBitmap(portrait, 0, 0, null);
            canvas.drawBitmap(portrait, 480, 0, null);
            for (int correction : new int[]{0, 90, 180, 270}) {
                Matrix rotate = new Matrix(); rotate.postRotate((360 - correction) % 360);
                Bitmap raw = Bitmap.createBitmap(crowd, 0, 0, 960, 640, rotate, false);
                List<Face> faces = await(joined.process(InputImage.fromBitmap(raw, correction)));
                require(faces.size() == 2, "reject two faces " + correction);
                require(new FaceFrameExtractor().extract(null, correction, faces).reason
                        == FrameQualityPolicy.Reason.MULTIPLE_FACES, "multiple-face gate");
                if (raw != crowd) raw.recycle();
            }
            crowd.recycle();
            result.putInt("orientationsPassed", orientations);
            result.putFloat("rotationMaxContourDeltaPixels", maximumContourDelta);
            result.putInt("multipleFaceOrientationsRejected", 4);
            result.putInt("comparedDetectorFrames", 28);
            result.putInt("detectorOutputMismatches", 0);
        } finally { portrait.recycle(); }
        String[] names = {"sequential", "parallel"};
        for (int lane = 0; lane < 2; lane++) {
            Arrays.sort(times[lane]);
            result.putLong(names[lane] + "DetectorMedianUs", times[lane][12] / 1000);
            result.putLong(names[lane] + "DetectorP95Us", times[lane][22] / 1000);
        }
    }

    private static List<Face> await(Task<List<Face>> task) throws Exception {
        return Tasks.await(task, 10, TimeUnit.SECONDS);
    }

    private static float sameFace(Face expected, Face actual, boolean rotated) {
        float maximumContourDelta = 0;
        require(expected.getBoundingBox().equals(actual.getBoundingBox()), "same bounding box");
        same(expected.getHeadEulerAngleX(), actual.getHeadEulerAngleX());
        same(expected.getHeadEulerAngleY(), actual.getHeadEulerAngleY());
        same(expected.getHeadEulerAngleZ(), actual.getHeadEulerAngleZ());
        same(expected.getLeftEyeOpenProbability(), actual.getLeftEyeOpenProbability());
        same(expected.getRightEyeOpenProbability(), actual.getRightEyeOpenProbability());
        require(expected.getAllLandmarks().size() == actual.getAllLandmarks().size(), "landmark count");
        for (FaceLandmark landmark : expected.getAllLandmarks()) {
            PointF a = landmark.getPosition(), b = actual.getLandmark(landmark.getLandmarkType()).getPosition();
            same(a.x, b.x); same(a.y, b.y);
        }
        require(expected.getAllContours().size() == actual.getAllContours().size(), "contour count");
        for (FaceContour contour : expected.getAllContours()) {
            List<PointF> a = contour.getPoints(), b = actual.getContour(contour.getFaceContourType()).getPoints();
            require(a.size() == b.size(), "contour point count");
            // Rotating the fixture can move individual SDK contour coordinates by 1px.
            // Keep serial/parallel comparison exact; check rotated eyes separately below.
            for (int i = 0; i < a.size(); i++) {
                float delta = Math.max(Math.abs(a.get(i).x - b.get(i).x),
                        Math.abs(a.get(i).y - b.get(i).y));
                require(delta <= (rotated ? 1f : 0f), "contour coordinate delta " + delta);
                maximumContourDelta = Math.max(maximumContourDelta, delta);
            }
        }
        float[] beforeRatios = new float[2], afterRatios = new float[2];
        int index = 0;
        for (int eye : new int[]{FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE}) {
            float before = FaceFrameExtractor.eyeContourRatio(expected.getContour(eye).getPoints(),
                    expected.getLandmark(FaceLandmark.LEFT_EYE).getPosition(),
                    expected.getLandmark(FaceLandmark.RIGHT_EYE).getPosition());
            float after = FaceFrameExtractor.eyeContourRatio(actual.getContour(eye).getPoints(),
                    actual.getLandmark(FaceLandmark.LEFT_EYE).getPosition(),
                    actual.getLandmark(FaceLandmark.RIGHT_EYE).getPosition());
            beforeRatios[index] = before; afterRatios[index++] = after;
        }
        require(PassiveEyeGate.problem(expected.getLeftEyeOpenProbability(),
                expected.getRightEyeOpenProbability(), beforeRatios[0], beforeRatios[1])
                == PassiveEyeGate.Problem.NONE, "fixture has open eyes");
        require(PassiveEyeGate.problem(actual.getLeftEyeOpenProbability(),
                actual.getRightEyeOpenProbability(), afterRatios[0], afterRatios[1])
                == PassiveEyeGate.Problem.NONE, "same open-eye gate in rotated frame");
        return maximumContourDelta;
    }

    private static void same(float expected, float actual) {
        require(Float.compare(expected, actual) == 0,
                "identical native measurement: " + expected + " vs " + actual);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

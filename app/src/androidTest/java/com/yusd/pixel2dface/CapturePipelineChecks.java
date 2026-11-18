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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Native pipeline comparison using a public-domain fixture, never the device enrollment. */
final class CapturePipelineChecks {
    static void run(Context testContext, Context targetContext, Bundle result) throws Exception {
        AlignedSamplingChecks.run(result);
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
            int independentDirections = 0, maximumSearchFrames = 0, totalSearchFrames = 0;
            int reacquisitionFrames = 0;
            for (int correction : new int[]{0, 90, 180, 270}) {
                Matrix rotate = new Matrix(); rotate.postRotate((360 - correction) % 360);
                Bitmap raw = Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(),
                        portrait.getHeight(), rotate, false);
                Matrix quarterTurn = new Matrix();
                quarterTurn.postRotate(-90);
                Bitmap turned = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), quarterTurn, false);
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
                            totalSearchFrames += frame;
                            found = true;
                            for (int stable = 0; stable < 5; stable++) {
                                require(direction.rotationFor(gravity) == correction, "stable capture direction");
                                direction.observe(1, faces.get(0).getHeadEulerAngleZ());
                            }
                            break;
                        }
                    }
                    require(found, "bedtime direction found " + correction + "/" + gravity);
                    // Turn the phone by 90 degrees with the person's posture held constant.
                    boolean reacquired = false;
                    for (int frame = 1; frame <= 4; frame++) {
                        int selected = direction.rotationFor((gravity + 90) % 360);
                        List<Face> faces = await(joined.process(InputImage.fromBitmap(turned, selected)));
                        float roll = faces.size() == 1 ? faces.get(0).getHeadEulerAngleZ() : 0;
                        direction.observe(faces.size(), roll);
                        if (faces.size() == 1 && Math.abs(roll) <= FrameQualityPolicy.MAX_ABS_ROLL) {
                            require(selected == (correction + 90) % 360, "face direction after quarter turn");
                            reacquisitionFrames += frame;
                            reacquired = true;
                            break;
                        }
                    }
                    require(reacquired, "reacquire after turning phone");
                    independentDirections++;
                }
                turned.recycle();
                if (raw != portrait) raw.recycle();
            }
            result.putInt("faceGravityCombinationsPassed", independentDirections);
            result.putInt("maximumDirectionSearchFrames", maximumSearchFrames);
            result.putInt("totalDirectionSearchFrames", totalSearchFrames);
            result.putInt("quarterTurnReacquisitionFrames", reacquisitionFrames);
            checkTiltedDirections(portrait, joined, result);
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
                require(new FaceFrameExtractor(false).extract(null, correction, faces).reason
                        == FrameQualityPolicy.Reason.MULTIPLE_FACES, "multiple-face gate");
                if (raw != crowd) raw.recycle();
            }
            crowd.recycle();
            result.putInt("orientationsPassed", orientations);
            result.putFloat("rotationMaxContourDeltaPixels", maximumContourDelta);
            result.putInt("multipleFaceOrientationsRejected", 4);
            result.putInt("comparedDetectorFrames", 28);
            result.putInt("detectorOutputMismatches", 0);
            compareVerification(targetContext, joined, portrait, result);
        } finally { portrait.recycle(); }
        String[] names = {"sequential", "parallel"};
        for (int lane = 0; lane < 2; lane++) {
            Arrays.sort(times[lane]);
            result.putLong(names[lane] + "DetectorMedianUs", times[lane][12] / 1000);
            result.putLong(names[lane] + "DetectorP95Us", times[lane][22] / 1000);
        }
    }

    private static void checkTiltedDirections(Bitmap portrait, SingleFaceDetector detector,
            Bundle result) throws Exception {
        int totalFrames = 0;
        for (int degrees : new int[]{-165, -75, 75, 165}) {
            Matrix rotate = new Matrix();
            rotate.postRotate(degrees);
            Bitmap tilted = Bitmap.createBitmap(portrait, 0, 0, portrait.getWidth(),
                    portrait.getHeight(), rotate, false);
            try {
                DetectionOrientation direction = new DetectionOrientation();
                boolean found = false;
                for (int frame = 1; frame <= 4; frame++) {
                    int selected = direction.rotationFor(0);
                    List<Face> faces = await(detector.process(InputImage.fromBitmap(tilted, selected)));
                    float roll = faces.size() == 1 ? faces.get(0).getHeadEulerAngleZ() : 0;
                    direction.observe(faces.size(), roll);
                    if (faces.size() == 1 && Math.abs(roll) <= FrameQualityPolicy.MAX_ABS_ROLL) {
                        require(selected == (360 - Math.round(degrees / 90f) * 90) % 360,
                                "tilted direction " + degrees);
                        totalFrames += frame;
                        found = true;
                        break;
                    }
                }
                require(found, "tilted face recovered " + degrees);
            } finally { tilted.recycle(); }
        }
        result.putInt("tiltedDirectionSearchFrames", totalFrames);
    }

    private static void compareVerification(Context context, SingleFaceDetector detector,
            Bitmap portrait, Bundle result) throws Exception {
        PortraitYuvFrame[] frames = new PortraitYuvFrame[5];
        for (int i = 0; i < frames.length; i++) frames[i] = new PortraitYuvFrame(portrait, i * 2);
        FaceFrameExtractor extractor = new FaceFrameExtractor(true);
        ExecutorService verifier = Executors.newSingleThreadExecutor();
        long[][] times = new long[2][12];
        try {
            MobileFaceEmbedder model = verifier.submit(() -> new MobileFaceEmbedder(context))
                    .get(10, TimeUnit.SECONDS);
            try {
                for (int round = -2; round < times[0].length; round++) {
                    float[][][] results = new float[2][][];
                    for (int step = 0; step < 2; step++) {
                        int lane = (round + 2 + step) % 2;
                        long start = SystemClock.elapsedRealtimeNanos();
                        results[lane] = verifyFiveFrames(detector, frames, extractor, model,
                                verifier, lane == 1);
                        if (round >= 0) times[lane][round] = SystemClock.elapsedRealtimeNanos() - start;
                    }
                    require(Arrays.deepEquals(results[0], results[1]),
                            "overlap changed the order or contents of the embeddings");
                    require(!Arrays.equals(results[1][0], results[1][4]),
                            "two distinct inputs must produce different embeddings");
                    if (round == 0) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        ByteBuffer vector = ByteBuffer.allocate(EmbeddingIdentity.DIMENSIONS * Float.BYTES)
                                .order(ByteOrder.LITTLE_ENDIAN);
                        for (float[] embedding : results[1]) {
                            vector.asFloatBuffer().put(embedding);
                            digest.update(vector.array());
                        }
                        result.putString("publicFixtureEmbeddingSha256", android.util.Base64
                                .encodeToString(digest.digest(), android.util.Base64.NO_WRAP));
                    }
                }
            } finally {
                verifier.submit(model::close).get(10, TimeUnit.SECONDS);
            }
        } finally {
            verifier.shutdown();
            extractor.clear();
        }
        String[] names = {"serialFiveFrames", "pipelinedFiveFrames"};
        for (int lane = 0; lane < 2; lane++) {
            Arrays.sort(times[lane]);
            result.putLong(names[lane] + "MedianUs",
                    (times[lane][5] + times[lane][6]) / 2000);
        }
        result.putInt("comparedPipelinedEmbeddings", 5 * (times[0].length + 2));
        result.putString("pipelineInput", "public portrait, NV21, aligned RGB");
    }

    private static float[][] verifyFiveFrames(SingleFaceDetector detector, PortraitYuvFrame[] frames,
            FaceFrameExtractor extractor, MobileFaceEmbedder model, ExecutorService verifier,
            boolean overlap) throws Exception {
        float[][] vectors = new float[5][];
        Future<float[]> previous = null;
        for (int frame = 0; frame < vectors.length; frame++) {
            PortraitYuvFrame image = frames[frame];
            List<Face> faces = await(detector.process(image.input));
            if (previous != null) vectors[frame - 1] = previous.get(10, TimeUnit.SECONDS);
            FaceFrameExtractor.Result sample = extractor.extract(image, 0, faces);
            require(sample.reason == FrameQualityPolicy.Reason.NONE, "public frame quality " + sample.reason);
            Future<float[]> current = verifier.submit(() -> model.embed(extractor.neuralInput));
            if (overlap) previous = current;
            else vectors[frame] = current.get(10, TimeUnit.SECONDS);
        }
        if (previous != null) vectors[vectors.length - 1] = previous.get(10, TimeUnit.SECONDS);
        return vectors;
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

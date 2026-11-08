package com.yusd.pixel2dface;

import android.graphics.PointF;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceLandmark;
import java.util.List;

/** Adapts ML Kit and CameraX measurements into the Android-independent recognition core. */
final class FaceFrameExtractor implements AutoCloseable {
    private final LbpDescriptor.Workspace workspace = new LbpDescriptor.Workspace();
    private final AlignedRgbSampler rgb = new AlignedRgbSampler();
    private MobileFaceEmbedder embedder;

    void initialize(android.content.Context context, boolean neural) throws java.io.IOException {
        if (neural) embedder = new MobileFaceEmbedder(context);
    }

    @Override public void close() {
        if (embedder != null) { embedder.close(); embedder = null; }
    }

    Result extract(ImageProxy frame, int rotation, List<Face> faces) {
        if (faces == null || faces.size() != 1) {
            return Result.reject(faces == null || faces.isEmpty()
                    ? FrameQualityPolicy.Reason.NO_FACE : FrameQualityPolicy.Reason.MULTIPLE_FACES);
        }
        int width = rotation == 90 || rotation == 270 ? frame.getHeight() : frame.getWidth();
        int height = rotation == 90 || rotation == 270 ? frame.getWidth() : frame.getHeight();
        Face face = faces.get(0);
        Rect detected = face.getBoundingBox();
        if (width <= 0 || height <= 0 || detected.width() <= 0 || detected.height() <= 0) {
            return Result.reject(FrameQualityPolicy.Reason.INVALID_SIGNALS);
        }
        float size = detected.width() / (float) width;
        float offsetX = Math.abs(detected.exactCenterX() - width * 0.5f) / width;
        float offsetY = Math.abs(detected.exactCenterY() - height * 0.5f) / height;
        PointF left = landmark(face, FaceLandmark.LEFT_EYE);
        PointF right = landmark(face, FaceLandmark.RIGHT_EYE);
        Float leftProbability = face.getLeftEyeOpenProbability();
        Float rightProbability = face.getRightEyeOpenProbability();
        float leftContour = eyeContourRatio(face, FaceContour.LEFT_EYE, left, right);
        float rightContour = eyeContourRatio(face, FaceContour.RIGHT_EYE, left, right);
        FrameQualityPolicy.Sample sample = new FrameQualityPolicy.Sample(1,
                leftProbability, rightProbability, leftContour, rightContour,
                face.getHeadEulerAngleY(), face.getHeadEulerAngleX(), face.getHeadEulerAngleZ(),
                size, offsetX, offsetY, 0, 0, 0);
        FrameQualityPolicy.Reason problem = FrameQualityPolicy.faceProblem(sample);
        if (problem != FrameQualityPolicy.Reason.NONE) return new Result(problem, sample, null, null);
        Rect crop = paddedAndClamped(detected, width, height);
        if (crop.width() < 100 || crop.height() < 100) {
            return new Result(FrameQualityPolicy.Reason.TOO_SMALL, sample, null, null);
        }
        PointF nose = landmark(face, FaceLandmark.NOSE_BASE);
        PointF mouthLeft = landmark(face, FaceLandmark.MOUTH_LEFT);
        PointF mouthRight = landmark(face, FaceLandmark.MOUTH_RIGHT);
        PointF mouthBottom = landmark(face, FaceLandmark.MOUTH_BOTTOM);
        if (left == null || right == null || nose == null || mouthLeft == null
                || mouthRight == null || mouthBottom == null) {
            return new Result(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR, sample, null, null);
        }
        float[] geometry = FaceGeometry.create(detected.width(), detected.height(),
                left.x, left.y, right.x, right.y, nose.x, nose.y, mouthLeft.x, mouthLeft.y,
                mouthRight.x, mouthRight.y, mouthBottom.x, mouthBottom.y);
        if (geometry == null) return new Result(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR,
                sample, null, null);
        if (embedder != null) {
            PointF firstEye = left.x < right.x ? left : right;
            PointF secondEye = left.x < right.x ? right : left;
            PointF firstMouth = mouthLeft.x < mouthRight.x ? mouthLeft : mouthRight;
            PointF secondMouth = mouthLeft.x < mouthRight.x ? mouthRight : mouthLeft;
            FaceAlignment alignment = FaceAlignment.fit(new float[]{firstEye.x, firstEye.y,
                    secondEye.x, secondEye.y, nose.x, nose.y, firstMouth.x, firstMouth.y,
                    secondMouth.x, secondMouth.y});
            ImageProxy.PlaneProxy[] planes = frame.getPlanes();
            if (planes == null || planes.length != 3) return new Result(
                    FrameQualityPolicy.Reason.INVALID_DESCRIPTOR, sample, null, null);
            if (!rgb.sample(planes[0].getBuffer(), planes[0].getRowStride(), planes[0].getPixelStride(),
                    planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                    planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride(),
                    frame.getWidth(), frame.getHeight(), rotation, alignment, embedder.input)) {
                return new Result(FrameQualityPolicy.Reason.INVALID_DESCRIPTOR, sample, null, null);
            }
            sample = new FrameQualityPolicy.Sample(1, leftProbability, rightProbability,
                    leftContour, rightContour, sample.yaw, sample.pitch, sample.roll, size,
                    offsetX, offsetY, rgb.brightness, rgb.contrast, rgb.sharpness);
            FrameQualityPolicy.Reason reason = FrameQualityPolicy.problem(sample);
            return new Result(reason, sample, reason == FrameQualityPolicy.Reason.NONE
                    ? embedder.embed() : null, geometry);
        }
        float[] texture = workspace.compute(frame, rotation, crop, left, right);
        sample = new FrameQualityPolicy.Sample(1, leftProbability, rightProbability,
                leftContour, rightContour, sample.yaw, sample.pitch, sample.roll, size,
                offsetX, offsetY, workspace.getBrightness(), workspace.getContrast(),
                workspace.getSharpness());
        return new Result(FrameQualityPolicy.problem(sample), sample, texture, geometry);
    }

    private static PointF landmark(Face face, int type) {
        FaceLandmark landmark = face.getLandmark(type);
        return landmark == null ? null : landmark.getPosition();
    }

    private static float eyeContourRatio(Face face, int type, PointF firstEye, PointF secondEye) {
        FaceContour contour = face.getContour(type);
        return contour == null ? 0 : eyeContourRatio(contour.getPoints(), firstEye, secondEye);
    }

    static float eyeContourRatio(List<PointF> points, PointF firstEye, PointF secondEye) {
        if (points.size() < 6 || firstEye == null || secondEye == null) return 0;
        float dx = secondEye.x - firstEye.x, dy = secondEye.y - firstEye.y;
        float distance = (float) Math.hypot(dx, dy);
        if (!Float.isFinite(distance) || distance == 0) return 0;
        float axisX = dx / distance, axisY = dy / distance;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (PointF point : points) {
            if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) return 0;
            // Measure in the face's own axes. Screen-space height counts head roll as
            // eyelid opening and can make an almost flat contour pass the open-eye gate.
            float x = point.x - firstEye.x, y = point.y - firstEye.y;
            float along = x * axisX + y * axisY;
            float across = -x * axisY + y * axisX;
            minX = Math.min(minX, along); maxX = Math.max(maxX, along);
            minY = Math.min(minY, across); maxY = Math.max(maxY, across);
        }
        return maxX <= minX ? 0 : (maxY - minY) / (maxX - minX);
    }

    private static Rect paddedAndClamped(Rect source, int width, int height) {
        int padX = Math.round(source.width() * .16f);
        return new Rect(Math.max(0, source.left - padX),
                Math.max(0, source.top - Math.round(source.height() * .20f)),
                Math.min(width, source.right + padX),
                Math.min(height, source.bottom + Math.round(source.height() * .10f)));
    }

    static final class Result {
        final FrameQualityPolicy.Reason reason;
        final FrameQualityPolicy.Sample sample;
        // Texture is borrowed from the workspace and only valid until the next extraction.
        final float[] texture;
        final float[] geometry;
        Result(FrameQualityPolicy.Reason reason, FrameQualityPolicy.Sample sample,
                float[] texture, float[] geometry) {
            this.reason = reason; this.sample = sample;
            this.texture = texture; this.geometry = geometry;
        }
        static Result reject(FrameQualityPolicy.Reason reason) {
            return new Result(reason, null, null, null);
        }
    }
}

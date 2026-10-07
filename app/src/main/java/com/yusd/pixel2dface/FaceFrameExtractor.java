package com.yusd.pixel2dface;

import android.graphics.PointF;
import android.graphics.Rect;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceLandmark;
import java.util.List;

/** Adapts ML Kit and CameraX measurements into the Android-independent recognition core. */
final class FaceFrameExtractor {
    private final LbpDescriptor.Workspace workspace = new LbpDescriptor.Workspace();

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
        Float leftProbability = face.getLeftEyeOpenProbability();
        Float rightProbability = face.getRightEyeOpenProbability();
        float leftContour = eyeContourRatio(face, FaceContour.LEFT_EYE);
        float rightContour = eyeContourRatio(face, FaceContour.RIGHT_EYE);
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
        PointF left = landmark(face, FaceLandmark.LEFT_EYE);
        PointF right = landmark(face, FaceLandmark.RIGHT_EYE);
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

    private static float eyeContourRatio(Face face, int type) {
        FaceContour contour = face.getContour(type);
        if (contour == null || contour.getPoints().size() < 6) return 0;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (PointF point : contour.getPoints()) {
            if (!Float.isFinite(point.x) || !Float.isFinite(point.y)) return 0;
            minX = Math.min(minX, point.x); maxX = Math.max(maxX, point.x);
            minY = Math.min(minY, point.y); maxY = Math.max(maxY, point.y);
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

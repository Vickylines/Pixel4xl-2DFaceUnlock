package com.yusd.pixel2dface;

/** Low-cost facial proportions derived from landmarks ML Kit already computed. */
final class FaceGeometry {
    static final int LENGTH = 7;

    private FaceGeometry() {
    }

    static float[] create(float boxWidth, float boxHeight,
            float leftEyeX, float leftEyeY, float rightEyeX, float rightEyeY,
            float noseX, float noseY, float mouthLeftX, float mouthLeftY,
            float mouthRightX, float mouthRightY, float mouthBottomX,
            float mouthBottomY) {
        if (!Float.isFinite(boxWidth) || !Float.isFinite(boxHeight)
                || boxWidth <= 1f || boxHeight <= 1f
                || !pointIsFinite(leftEyeX, leftEyeY)
                || !pointIsFinite(rightEyeX, rightEyeY) || !pointIsFinite(noseX, noseY)
                || !pointIsFinite(mouthLeftX, mouthLeftY)
                || !pointIsFinite(mouthRightX, mouthRightY)
                || !pointIsFinite(mouthBottomX, mouthBottomY)) {
            return null;
        }
        if (leftEyeX > rightEyeX) {
            float swapX = leftEyeX;
            float swapY = leftEyeY;
            leftEyeX = rightEyeX;
            leftEyeY = rightEyeY;
            rightEyeX = swapX;
            rightEyeY = swapY;
        }
        float eyeDx = rightEyeX - leftEyeX;
        float eyeDy = rightEyeY - leftEyeY;
        float eyeDistance = hypot(eyeDx, eyeDy);
        if (!Float.isFinite(eyeDistance) || eyeDistance < 8f) {
            return null;
        }
        float axisX = eyeDx / eyeDistance;
        float axisY = eyeDy / eyeDistance;
        float downX = -axisY;
        float downY = axisX;
        float eyeMidX = (leftEyeX + rightEyeX) * 0.5f;
        float eyeMidY = (leftEyeY + rightEyeY) * 0.5f;
        float mouthMidX = (mouthLeftX + mouthRightX) * 0.5f;
        float mouthMidY = (mouthLeftY + mouthRightY) * 0.5f;

        float[] result = new float[] {
                boxWidth / boxHeight,
                eyeDistance / boxWidth,
                project(noseX - eyeMidX, noseY - eyeMidY, downX, downY) / boxHeight,
                project(mouthMidX - eyeMidX, mouthMidY - eyeMidY, downX, downY)
                        / boxHeight,
                hypot(mouthRightX - mouthLeftX, mouthRightY - mouthLeftY) / boxWidth,
                project(mouthBottomX - noseX, mouthBottomY - noseY, downX, downY)
                        / boxHeight,
                project(noseX - eyeMidX, noseY - eyeMidY, axisX, axisY) / boxWidth
        };
        return isValid(result) ? result : null;
    }

    static boolean isValid(float[] values) {
        if (values == null || values.length != LENGTH) return false;
        for (float value : values) {
            if (!Float.isFinite(value) || value < -2f || value > 2f) return false;
        }
        return true;
    }

    private static boolean pointIsFinite(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y);
    }

    private static float project(float x, float y, float axisX, float axisY) {
        return x * axisX + y * axisY;
    }

    private static float hypot(float x, float y) {
        return (float) Math.hypot(x, y);
    }
}

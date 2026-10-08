package com.yusd.pixel2dface;

/** Android-independent gates shared by enrollment and recognition. */
final class FrameQualityPolicy {
    static final float MAX_ABS_YAW = 28f;
    static final float MAX_ABS_PITCH = 22f;
    static final float MAX_ABS_ROLL = 25f;
    static final float MIN_FACE_WIDTH_RATIO = 0.18f;
    static final float MAX_CENTER_OFFSET_X = 0.34f;
    static final float MAX_CENTER_OFFSET_Y = 0.36f;
    static final float MIN_BRIGHTNESS = 12f;
    static final float MAX_BRIGHTNESS = 248f;
    static final float MIN_CONTRAST = 6f;
    static final float MIN_SHARPNESS = 1.5f;

    private FrameQualityPolicy() { }

    enum Reason {
        NONE(""), NO_FACE("未检测到人脸"), MULTIPLE_FACES("画面中只能有一张人脸"),
        TOO_SMALL("请靠近一点"), OFF_CENTER("请将脸移到画面中央"),
        EYES_CLOSED("请自然睁眼并看向屏幕"), POSE("请自然正视屏幕"),
        TOO_DARK("光线太暗，请稍微提高屏幕亮度"),
        TOO_BRIGHT("画面过亮，请避开强光"), LOW_CONTRAST("面部光线不足，请面向屏幕"),
        BLURRED("画面不够清晰，请保持手机稳定"), INVALID_SIGNALS("画面数据无效，请重试"),
        INVALID_DESCRIPTOR("请让完整面部正对屏幕"),
        STALE_SESSION("识别会话已结束"), STALE_FRAME("画面已过期，请保持手机稳定"),
        INVALID_TIME("画面时间无效，请重试"),
        IDENTITY_MISMATCH("人脸未匹配，请自然正视屏幕");

        final String message;
        Reason(String message) { this.message = message; }
    }

    /** Scalar measurements only; does not retain camera images, landmarks or descriptors. */
    static final class Sample {
        final int faceCount;
        final Float leftEyeProbability;
        final Float rightEyeProbability;
        final float leftEyeContourRatio;
        final float rightEyeContourRatio;
        final float yaw;
        final float pitch;
        final float roll;
        final float faceWidthRatio;
        final float centerOffsetX;
        final float centerOffsetY;
        final float brightness;
        final float contrast;
        final float sharpness;

        Sample(int faceCount, Float leftEyeProbability, Float rightEyeProbability,
                float leftEyeContourRatio, float rightEyeContourRatio,
                float yaw, float pitch, float roll, float faceWidthRatio,
                float centerOffsetX, float centerOffsetY,
                float brightness, float contrast, float sharpness) {
            this.faceCount = faceCount;
            this.leftEyeProbability = leftEyeProbability;
            this.rightEyeProbability = rightEyeProbability;
            this.leftEyeContourRatio = leftEyeContourRatio;
            this.rightEyeContourRatio = rightEyeContourRatio;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.faceWidthRatio = faceWidthRatio;
            this.centerOffsetX = centerOffsetX;
            this.centerOffsetY = centerOffsetY;
            this.brightness = brightness;
            this.contrast = contrast;
            this.sharpness = sharpness;
        }
    }

    /** Safe to run before computing the descriptor; image statistics may be absent. */
    static Reason faceProblem(Sample sample) {
        if (sample == null || sample.faceCount < 0) return Reason.INVALID_SIGNALS;
        if (sample.faceCount == 0) return Reason.NO_FACE;
        if (sample.faceCount != 1) return Reason.MULTIPLE_FACES;
        if (!finite(sample.yaw, sample.pitch, sample.roll)
                || !finite(sample.faceWidthRatio, sample.centerOffsetX, sample.centerOffsetY)
                || sample.faceWidthRatio <= 0f || sample.centerOffsetX < 0f
                || sample.centerOffsetY < 0f) return Reason.INVALID_SIGNALS;
        if (sample.faceWidthRatio < MIN_FACE_WIDTH_RATIO) return Reason.TOO_SMALL;
        if (sample.centerOffsetX > MAX_CENTER_OFFSET_X
                || sample.centerOffsetY > MAX_CENTER_OFFSET_Y) return Reason.OFF_CENTER;
        if (!PassiveEyeGate.areBothEyesOpen(sample.leftEyeProbability,
                sample.rightEyeProbability, sample.leftEyeContourRatio,
                sample.rightEyeContourRatio)) return Reason.EYES_CLOSED;
        if (Math.abs(sample.yaw) > MAX_ABS_YAW || Math.abs(sample.pitch) > MAX_ABS_PITCH
                || Math.abs(sample.roll) > MAX_ABS_ROLL) return Reason.POSE;
        return Reason.NONE;
    }

    static Reason imageProblem(Sample sample) {
        if (sample == null || !finite(sample.brightness, sample.contrast, sample.sharpness)
                || sample.brightness < 0f || sample.brightness > 255f
                || sample.contrast < 0f || sample.sharpness < 0f) return Reason.INVALID_SIGNALS;
        if (sample.brightness < MIN_BRIGHTNESS) return Reason.TOO_DARK;
        if (sample.brightness > MAX_BRIGHTNESS) return Reason.TOO_BRIGHT;
        if (sample.contrast < MIN_CONTRAST) return Reason.LOW_CONTRAST;
        if (sample.sharpness < MIN_SHARPNESS) return Reason.BLURRED;
        return Reason.NONE;
    }

    static Reason problem(Sample sample) {
        Reason face = faceProblem(sample);
        return face == Reason.NONE ? imageProblem(sample) : face;
    }

    static float posePenalty(Sample sample) {
        return Math.min(0.028f,
                scaled(Math.abs(sample.yaw), 14f, MAX_ABS_YAW, 0.014f)
                + scaled(Math.abs(sample.pitch), 12f, MAX_ABS_PITCH, 0.010f)
                + scaled(Math.abs(sample.roll), 12f, MAX_ABS_ROLL, 0.008f));
    }

    static float qualityPenalty(Sample sample) {
        float penalty = 0f;
        if (sample.brightness < 30f) {
            penalty += scaled(30f - sample.brightness, 0f, 18f, 0.010f);
        } else if (sample.brightness > 230f) {
            penalty += scaled(sample.brightness - 230f, 0f, 18f, 0.010f);
        }
        if (sample.contrast < 12f) penalty += scaled(12f - sample.contrast, 0f, 6f, 0.012f);
        if (sample.sharpness < 3.2f) penalty += scaled(3.2f - sample.sharpness, 0f, 1.7f, 0.012f);
        return Math.min(0.026f, penalty);
    }

    static float framingPenalty(Sample sample) {
        float penalty = 0f;
        if (sample.faceWidthRatio < 0.24f) {
            penalty += scaled(0.24f - sample.faceWidthRatio, 0f, 0.06f, 0.008f);
        }
        if (sample.centerOffsetX > 0.25f) {
            penalty += scaled(sample.centerOffsetX - 0.25f, 0f, 0.09f, 0.006f);
        }
        if (sample.centerOffsetY > 0.27f) {
            penalty += scaled(sample.centerOffsetY - 0.27f, 0f, 0.09f, 0.006f);
        }
        return Math.min(0.014f, penalty);
    }

    static float frameThreshold(float baseThreshold, Sample sample) {
        return Math.max(0.24f, baseThreshold - posePenalty(sample)
                - qualityPenalty(sample) - framingPenalty(sample));
    }

    static boolean highQuality(Sample sample) {
        return problem(sample) == Reason.NONE && posePenalty(sample) == 0f
                && qualityPenalty(sample) == 0f && framingPenalty(sample) == 0f
                && PassiveEyeGate.areConfidentlyOpen(sample.leftEyeProbability,
                        sample.rightEyeProbability, sample.leftEyeContourRatio,
                        sample.rightEyeContourRatio);
    }

    private static boolean finite(float first, float second, float third) {
        return Float.isFinite(first) && Float.isFinite(second) && Float.isFinite(third);
    }

    private static float scaled(float value, float free, float hard, float maximum) {
        return value <= free ? 0f : Math.min(maximum, (value - free) / (hard - free) * maximum);
    }
}

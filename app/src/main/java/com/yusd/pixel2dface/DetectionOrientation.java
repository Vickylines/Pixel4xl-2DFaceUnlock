package com.yusd.pixel2dface;

/** Gravity is a starting direction, not a requirement to sit upright in bed. */
final class DetectionOrientation {
    private int sensorRotation = -1;
    private int selectedRotation;
    private boolean faceDirectionFound;
    private int missingFrames;
    private int triedDirections;

    int rotationFor(int cameraRotation) {
        if (sensorRotation != cameraRotation) {
            // When the phone turns, the last visible face usually keeps its angle to gravity.
            // Carry that offset only from a fresh single-face observation in this session.
            selectedRotation = faceDirectionFound && missingFrames == 0
                    ? (cameraRotation + selectedRotation - sensorRotation + 360) % 360
                    : cameraRotation;
            sensorRotation = cameraRotation;
            faceDirectionFound = false;
            missingFrames = 0;
            triedDirections = 0;
        }
        return selectedRotation;
    }

    void observe(int faceCount, float roll) {
        // Search using detection alone, never identity scores or eye-open probabilities.
        // Each fresh frame is evaluated exactly once. A detected crowd never selects
        // another direction that might hide one of its faces.
        if (faceCount == 0) {
            // A single missed detection must not send an already aligned face through
            // three wrong directions. The rejected frame still clears recognition votes.
            if (faceDirectionFound && ++missingFrames < 2) return;
            faceDirectionFound = false;
            tryDirection(1);
        } else if (faceCount == 1 && Math.abs(roll) > 45f && Math.abs(roll) <= 180f) {
            faceDirectionFound = false;
            missingFrames = 0;
            // Positive ML Kit Z is counterclockwise in the processed image.
            // Apply the nearest clockwise correction to the NEXT camera frame.
            int turns = Math.round(Math.abs(roll) / 90f);
            tryDirection(roll > 0f ? turns : -turns);
        } else {
            missingFrames = 0;
            faceDirectionFound = faceCount == 1 && Math.abs(roll) <= FrameQualityPolicy.MAX_ABS_ROLL;
            if (faceDirectionFound) triedDirections = 0;
        }
    }

    private void tryDirection(int turns) {
        int current = selectedRotation / 90;
        triedDirections |= 1 << current;
        if (triedDirections == 0b1111) triedDirections = 1 << current;
        int next = (current + turns + 4) % 4;
        // A noisy angle must not bounce between two directions indefinitely.
        while ((triedDirections & (1 << next)) != 0) next = (next + 1) % 4;
        selectedRotation = next * 90;
    }
}

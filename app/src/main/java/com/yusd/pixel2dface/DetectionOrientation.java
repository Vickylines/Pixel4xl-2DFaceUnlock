package com.yusd.pixel2dface;

/** Gravity is a starting direction, not a requirement to sit upright in bed. */
final class DetectionOrientation {
    private int sensorRotation = -1;
    private int selectedRotation;
    private boolean faceDirectionFound;
    private int missingFrames;

    int rotationFor(int cameraRotation) {
        if (sensorRotation != cameraRotation) {
            sensorRotation = cameraRotation;
            selectedRotation = cameraRotation;
            faceDirectionFound = false;
            missingFrames = 0;
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
            selectedRotation = (selectedRotation + 90) % 360;
        } else if (faceCount == 1 && Float.isFinite(roll) && Math.abs(roll) > 45f) {
            faceDirectionFound = false;
            missingFrames = 0;
            selectedRotation = (selectedRotation + 90) % 360;
        } else {
            missingFrames = 0;
            if (faceCount == 1 && Float.isFinite(roll) && Math.abs(roll) <= 25f) {
                faceDirectionFound = true;
            }
        }
    }
}

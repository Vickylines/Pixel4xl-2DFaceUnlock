package com.yusd.pixel2dface;

import android.view.Surface;
import androidx.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Physical device orientation, independent of the lock screen's display rotation. */
final class CaptureRotation {
    @IntDef({Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270})
    @Retention(RetentionPolicy.SOURCE)
    @interface SurfaceRotation { }

    private int surfaceRotation;
    private boolean hasOrientation;

    CaptureRotation(int initialSurfaceRotation) {
        surfaceRotation = initialSurfaceRotation;
    }

    @SurfaceRotation int surfaceRotation() { return surfaceRotation; }

    boolean update(int orientation) {
        if (orientation == -1) return false; // Android cannot determine orientation when flat.
        int previousDegrees = (360 - surfaceRotation * 90) % 360;
        int distance = Math.abs(orientation - previousDegrees);
        distance = Math.min(distance, 360 - distance);
        // Avoid flipping back and forth at the 45-degree boundaries while being held.
        if (hasOrientation && distance < 55) return false;
        hasOrientation = true;
        int next = (4 - (orientation + 45) / 90) % 4;
        if (next == surfaceRotation) return false;
        surfaceRotation = next;
        return true;
    }
}

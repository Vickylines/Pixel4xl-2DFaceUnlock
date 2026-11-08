package com.yusd.pixel2dface;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import org.junit.Test;

public final class LumaPlaneTest {
    @Test public void respectsNonzeroPositionPaddedRowsAndPixelStride() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{99, 99, 0, 99, 100, 99, 99, 99,
                (byte) 200, 99, (byte) 255});
        buffer.position(2);
        LumaPlane plane = new LumaPlane();
        plane.bind(buffer, 2, 2, 6, 2);
        assertEquals(0, plane.sample(0f, 0f));
        assertEquals(100, plane.sample(1f, 0f));
        assertEquals(200, plane.sample(0f, 1f));
        assertEquals(255, plane.sample(1f, 1f));
        assertEquals(139, plane.sample(0.5f, 0.5f));
        assertEquals(2, buffer.position());
    }

    @Test public void clampsFiniteCoordinatesAtTheCameraEdge() {
        LumaPlane plane = new LumaPlane();
        plane.bind(ByteBuffer.wrap(new byte[]{0, 50, 100, (byte) 255}), 2, 2, 2, 1);
        assertEquals(0, plane.sample(-100f, -100f));
        assertEquals(255, plane.sample(100f, 100f));
    }

    @Test public void rejectsUnavailableTruncatedAndOverflowingPlanesBeforeSampling() {
        LumaPlane plane = new LumaPlane();
        assertThrows(IllegalArgumentException.class, () -> plane.bind(null, 2, 2, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> plane.bind(ByteBuffer.allocate(3), 2, 2, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> plane.bind(ByteBuffer.allocate(16), 3, 2, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> plane.bind(ByteBuffer.allocate(16), 2, Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class,
                () -> plane.bind(ByteBuffer.allocate(16), Integer.MAX_VALUE, 2, 4, Integer.MAX_VALUE));
    }

    @Test public void validatesBufferLimitRatherThanCapacity() {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.limit(3);
        assertThrows(IllegalArgumentException.class, () -> new LumaPlane().bind(buffer, 2, 2, 2, 1));
    }

    @Test public void invalidCoordinatesAndReleasedViewsCannotSampleOldBuffers() {
        LumaPlane plane = new LumaPlane();
        plane.bind(ByteBuffer.allocate(4), 2, 2, 2, 1);
        assertThrows(IllegalArgumentException.class, () -> plane.sample(Float.NaN, 0f));
        assertThrows(IllegalArgumentException.class, () -> plane.sample(0f, Float.POSITIVE_INFINITY));
        plane.unbind();
        assertThrows(IllegalArgumentException.class, () -> plane.sample(0f, 0f));
    }
}

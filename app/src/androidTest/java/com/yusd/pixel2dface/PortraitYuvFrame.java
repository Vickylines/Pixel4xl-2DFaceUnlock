package com.yusd.pixel2dface;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import com.google.mlkit.vision.common.InputImage;
import java.nio.ByteBuffer;

/** Both test readers see the same pixels from the bundled public-domain portrait. */
final class PortraitYuvFrame implements ImageProxy {
    final InputImage input;
    private final int width, height;
    private final PlaneProxy[] planes;

    PortraitYuvFrame(Bitmap bitmap, int extraLuma) {
        width = bitmap.getWidth();
        height = bitmap.getHeight();
        int area = width * height;
        int[] pixels = new int[area];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        ByteBuffer packed = ByteBuffer.allocateDirect(area * 3 / 2);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int color = pixels[y * width + x];
                int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
                int luma = 16 + ((66 * r + 129 * g + 25 * b + 128) >> 8);
                packed.put(y * width + x, (byte) Math.min(235, luma + extraLuma));
                if ((y % 2 == 0) && (x % 2 == 0)) {
                    int offset = area + (y / 2) * width + x;
                    packed.put(offset, (byte) (128 + ((112 * r - 94 * g - 18 * b + 128) >> 8)));
                    packed.put(offset + 1, (byte) (128 + ((-38 * r - 74 * g + 112 * b + 128) >> 8)));
                }
            }
        }
        input = InputImage.fromByteBuffer(packed.duplicate(), width, height, 0, ImageFormat.NV21);
        planes = new PlaneProxy[]{plane(packed, 0, area, 1), plane(packed, area + 1, packed.limit(), 2),
                plane(packed, area, packed.limit() - 1, 2)};
    }

    private PlaneProxy plane(ByteBuffer packed, int start, int end, int pixelStride) {
        ByteBuffer view = packed.duplicate();
        view.position(start).limit(end);
        ByteBuffer pixels = view.slice();
        return new PlaneProxy() {
            @Override public int getRowStride() { return width; }
            @Override public int getPixelStride() { return pixelStride; }
            @Override public ByteBuffer getBuffer() { return pixels; }
        };
    }

    @Override public int getWidth() { return width; }
    @Override public int getHeight() { return height; }
    @Override public int getFormat() { return ImageFormat.YUV_420_888; }
    @Override public PlaneProxy[] getPlanes() { return planes; }
    @Override public Rect getCropRect() { return new Rect(0, 0, width, height); }
    @Override public void setCropRect(Rect rect) { throw new UnsupportedOperationException(); }
    @Override public ImageInfo getImageInfo() { throw new UnsupportedOperationException(); }
    @Override @ExperimentalGetImage public Image getImage() { return null; }
    @Override public void close() {}
}

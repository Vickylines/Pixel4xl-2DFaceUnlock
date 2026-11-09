package com.yusd.pixel2dface;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.Image;
import com.google.android.gms.common.Feature;
import com.google.android.gms.internal.mlkit_vision_face.zzow;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.odml.image.MlImage;
import com.google.mlkit.common.sdkinternal.MlKitContext;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetector;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class SingleFaceDetectorTest {
    private final PendingDetector population = new PendingDetector();
    private final PendingDetector detail = new PendingDetector();
    private final AtomicBoolean closing = new AtomicBoolean();
    private InputImage image;
    private final SingleFaceDetector detector =
            new SingleFaceDetector(population, detail, Runnable::run, closing::get);

    @Before public void initializeImage() {
        MlKitContext.initializeIfNeeded(RuntimeEnvironment.getApplication());
        image = InputImage.fromByteArray(new byte[480 * 360 * 3 / 2],
                480, 360, 0, ImageFormat.NV21);
    }

    @Test public void multipleFacesNeverReachTheSingleFaceContourStage() {
        Task<List<Face>> task = detector.process(image);
        List<Face> faces = List.of(face(0), face(160));
        population.result.setResult(faces);
        assertSame(faces, task.getResult());
        assertEquals(0, detail.calls);
        assertEquals(FrameQualityPolicy.Reason.MULTIPLE_FACES,
                new FaceFrameExtractor().extract(null, 0, task.getResult()).reason);
    }

    @Test public void noFaceSkipsContoursAndReportsNoFace() {
        Task<List<Face>> task = detector.process(image);
        population.result.setResult(List.of());
        assertTrue(task.getResult().isEmpty());
        assertEquals(0, detail.calls);
        assertEquals(FrameQualityPolicy.Reason.NO_FACE,
                new FaceFrameExtractor().extract(null, 0, task.getResult()).reason);
    }

    @Test public void oneFaceWaitsForDetailsFromTheSameImage() {
        Task<List<Face>> task = detector.process(image);
        assertEquals(0, detail.calls);
        population.result.setResult(List.of(face(0)));
        assertSame(image, population.received);
        assertSame(image, detail.received);
        assertEquals(1, detail.calls);
        assertFalse(task.isComplete());
        List<Face> detailed = List.of(face(0));
        detail.result.setResult(detailed);
        assertSame(detailed, task.getResult());
    }

    @Test public void countingFailureCannotFallBackToProminentFace() {
        Task<List<Face>> task = detector.process(image);
        IllegalStateException failure = new IllegalStateException("counting failed");
        population.result.setException(failure);
        assertSame(failure, task.getException());
        assertEquals(0, detail.calls);
    }

    @Test public void contourFailureCannotReturnTheCountOnlyFace() {
        Task<List<Face>> task = detector.process(image);
        population.result.setResult(List.of(face(0)));
        IllegalStateException failure = new IllegalStateException("contours failed");
        detail.result.setException(failure);
        assertSame(failure, task.getException());
        assertFalse(task.isSuccessful());
    }

    @Test public void stoppingDuringCountingDoesNotStartAnotherNativeTask() {
        Task<List<Face>> task = detector.process(image);
        closing.set(true);
        population.result.setResult(List.of(face(0)));
        assertTrue(task.isComplete());
        assertEquals(0, detail.calls);
        detector.close();
        assertTrue(population.closed);
        assertTrue(detail.closed);
    }

    @Test public void bothNativeClientsAreReleasedEvenIfTheFirstCloseFails() {
        population.failClose = true;
        assertThrows(IllegalStateException.class, detector::close);
        assertTrue(population.closed);
        assertTrue(detail.closed);
    }

    @Test public void warmUpLoadsBothClientsWithoutReusingACameraImage() {
        Task<List<Face>> task = detector.warmUp();
        assertNotSame(image, population.received);
        assertEquals(64, population.received.getWidth());
        population.result.setResult(List.of());
        assertSame(population.received, detail.received);
        assertFalse(task.isComplete());
        detail.result.setResult(List.of());
        assertTrue(task.isSuccessful());
    }

    @Test public void cancelledWarmUpDoesNotStartTheSecondClient() {
        Task<List<Face>> task = detector.warmUp();
        closing.set(true);
        population.result.setResult(List.of());
        assertTrue(task.isComplete());
        assertEquals(0, detail.calls);
    }

    @Test public void warmUpFailurePropagatesToTheCaptureOwner() {
        Task<List<Face>> task = detector.warmUp();
        IllegalStateException failure = new IllegalStateException("native model failed");
        population.result.setException(failure);
        assertSame(failure, task.getException());
        assertEquals(0, detail.calls);
    }

    private static Face face(int left) {
        // Synthetic ML Kit result using the constructor in the project's pinned SDK.
        return new Face(new zzow(0, new Rect(left, 40, left + 120, 180),
                0, 0, 0, .9f, .9f, 0, 0, List.of(), List.of()), new Matrix());
    }

    /** Controlled completion of the two asynchronous SDK calls, without native camera access. */
    private static final class PendingDetector implements FaceDetector {
        final TaskCompletionSource<List<Face>> result = new TaskCompletionSource<>();
        InputImage received;
        int calls;
        boolean closed;
        boolean failClose;

        @Override public Task<List<Face>> process(InputImage input) {
            received = input;
            calls++;
            return result.getTask();
        }
        @Override public void close() {
            closed = true;
            if (failClose) throw new IllegalStateException("close failed");
        }
        @Override public int getDetectorType() { return TYPE_FACE_DETECTION; }
        @Override public Feature[] getOptionalFeatures() { return new Feature[0]; }
        @Override public Task<List<Face>> process(MlImage input) { throw new UnsupportedOperationException(); }
        @Override public Task<List<Face>> process(Bitmap input, int rotation) { throw new UnsupportedOperationException(); }
        @Override public Task<List<Face>> process(Image input, int rotation) { throw new UnsupportedOperationException(); }
        @Override public Task<List<Face>> process(Image input, int rotation, Matrix matrix) {
            throw new UnsupportedOperationException();
        }
        @Override public Task<List<Face>> process(ByteBuffer input, int width, int height, int rotation, int format) {
            throw new UnsupportedOperationException();
        }
    }
}

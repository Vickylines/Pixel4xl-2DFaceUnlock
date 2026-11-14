package com.yusd.pixel2dface;

/** Uses the camera's declared clock or estimates its offset from the quickest delivery. */
final class FrameTimestampNormalizer {
    private final boolean realtime;
    private long firstSensorNs = -1;
    private long minimumOffsetMs;
    private long lastSensorNs = -1;
    private long lastArrivalMs = -1;
    private long lastNormalizedMs = -1;

    FrameTimestampNormalizer() { this(false); }

    FrameTimestampNormalizer(boolean realtime) { this.realtime = realtime; }

    long normalize(long sensorNs, long arrivalMs) {
        if (sensorNs < 0 || arrivalMs < 0 || sensorNs <= lastSensorNs
                || arrivalMs < lastArrivalMs) return -1;
        long normalizedMs = realtime ? sensorNs / 1_000_000L : estimateTimestamp(sensorNs, arrivalMs);
        if (normalizedMs > arrivalMs) return -1;
        lastSensorNs = sensorNs;
        lastArrivalMs = arrivalMs;
        // Consume the sensor watermark even if multiple frames fall in the same millisecond.
        if (normalizedMs < 0 || normalizedMs <= lastNormalizedMs) return -1;
        lastNormalizedMs = normalizedMs;
        return normalizedMs;
    }

    private long estimateTimestamp(long sensorNs, long arrivalMs) {
        if (firstSensorNs < 0) {
            firstSensorNs = sensorNs;
            minimumOffsetMs = arrivalMs;
        }
        // Both sensor values are nonnegative and increasing, so subtraction cannot overflow.
        // Keep nanosecond differences before conversion to preserve the original precision.
        long deltaMs = (sensorNs - firstSensorNs) / 1_000_000L;
        long observedOffsetMs = arrivalMs - deltaMs;
        minimumOffsetMs = Math.min(minimumOffsetMs, observedOffsetMs);
        // The minimum includes this arrival: offset + delta <= arrival, so the sum cannot
        // overflow or become a future frame. It only moves downward when startup/queue
        // latency falls; a slow callback never shifts following capture times into the future.
        return minimumOffsetMs + deltaMs;
    }
}

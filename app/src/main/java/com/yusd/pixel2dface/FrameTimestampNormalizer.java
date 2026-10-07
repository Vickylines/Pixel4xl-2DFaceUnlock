package com.yusd.pixel2dface;

/** Maps the camera's opaque epoch using the smallest observed delivery delay per session. */
final class FrameTimestampNormalizer {
    private long firstSensorNs = -1;
    private long minimumOffsetMs;
    private long lastSensorNs = -1;
    private long lastArrivalMs = -1;
    private long lastNormalizedMs = -1;

    long normalize(long sensorNs, long arrivalMs) {
        if (sensorNs < 0 || arrivalMs < 0 || sensorNs <= lastSensorNs
                || arrivalMs < lastArrivalMs) return -1;
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
        long normalizedMs = minimumOffsetMs + deltaMs;
        lastSensorNs = sensorNs;
        lastArrivalMs = arrivalMs;
        // Distinct sensor ns can still map to the same millisecond. Consume their watermark
        // but never return duplicate/backwards evidence, including equal-arrival bursts.
        if (normalizedMs < 0 || normalizedMs <= lastNormalizedMs) return -1;
        lastNormalizedMs = normalizedMs;
        return normalizedMs;
    }
}

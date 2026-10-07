package com.yusd.pixel2dface;

/** Serializes the lifetime of a detector with asynchronous frame completion. */
final class CaptureSessionGate {
    private boolean closing;
    private boolean inFlight;
    private boolean released;

    synchronized boolean acquireFrame() {
        if (closing || released || inFlight) return false;
        inFlight = true;
        return true;
    }

    synchronized void completeFrame() { inFlight = false; }

    synchronized void requestClose() { closing = true; }

    synchronized boolean isClosing() { return closing; }

    synchronized boolean claimResourceRelease() {
        if (!closing || inFlight || released) return false;
        released = true;
        return true;
    }
}

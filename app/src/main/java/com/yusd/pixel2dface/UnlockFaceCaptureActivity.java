package com.yusd.pixel2dface;

/** Exported camera host used only for a one-shot session authorized by SystemUI. */
public final class UnlockFaceCaptureActivity extends FaceCaptureActivity {
    @Override
    protected boolean isExternalUnlockHost() {
        return true;
    }
}

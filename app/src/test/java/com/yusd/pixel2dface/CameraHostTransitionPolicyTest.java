package com.yusd.pixel2dface;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CameraHostTransitionPolicyTest {
    @Test public void onlyTheExactUnlockHostOwnsOcclusion() {
        assertTrue(CameraHostTransitionPolicy.isHost(Constants.PACKAGE_NAME, Constants.UNLOCK_HOST_ACTIVITY));
        assertFalse(CameraHostTransitionPolicy.isHost(Constants.PACKAGE_NAME, Constants.PACKAGE_NAME + ".MainActivity"));
        assertFalse(CameraHostTransitionPolicy.isHost("com.android.dialer", Constants.UNLOCK_HOST_ACTIVITY));
        assertFalse(CameraHostTransitionPolicy.isHost(null, null));
    }

    @Test public void openingHostMayHaveAClosingLauncherInTheSameTransition() {
        assertTrue(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{0, 1}, new boolean[]{true, false}));
        assertTrue(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{1, 0}, new boolean[]{false, true}));
    }

    @Test public void phoneOrAlarmOpeningAlongsideOrInsteadOfTheHostRunsNatively() {
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{0, 0}, new boolean[]{true, false}));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{1, 0}, new boolean[]{true, false}));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{0, 2}, new boolean[]{true, false}));
    }

    @Test public void unknownOrMissingTargetsDoNotProveHostOwnership() {
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(null, null));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[0], new boolean[0]));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{0}, new boolean[0]));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{42}, new boolean[]{true}));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{1}, new boolean[]{true}));
        assertFalse(CameraHostTransitionPolicy.ownsOpeningTargets(new int[]{2}, new boolean[]{true}));
    }
}

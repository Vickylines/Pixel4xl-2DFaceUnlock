package com.yusd.pixel2dface;

/** Ownership checks for visual occlusion only. This policy cannot authorize an unlock. */
final class CameraHostTransitionPolicy {
    static final int OPENING = 0;
    static final int CLOSING = 1;
    static final int CHANGING = 2;

    private CameraHostTransitionPolicy() { }

    static boolean isHost(String packageName, String className) {
        return Constants.PACKAGE_NAME.equals(packageName)
                && Constants.UNLOCK_HOST_ACTIVITY.equals(className);
    }

    static boolean ownsOpeningTargets(int[] modes, boolean[] hosts) {
        if (modes == null || hosts == null || modes.length == 0 || modes.length != hosts.length) return false;
        boolean openingHost = false;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == CLOSING) continue;
            if ((modes[i] != OPENING && modes[i] != CHANGING) || !hosts[i]) return false;
            if (modes[i] == OPENING) openingHost = true;
        }
        return openingHost;
    }
}

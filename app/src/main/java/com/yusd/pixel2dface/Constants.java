package com.yusd.pixel2dface;

public final class Constants {
    public static final String PACKAGE_NAME = "com.yusd.pixel2dface";
    public static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    public static final String PROVIDER_URI = "content://com.yusd.pixel2dface.config";
    public static final String ACTION_UNLOCK_RESULT = PACKAGE_NAME + ".ACTION_UNLOCK_RESULT";
    public static final String ACTION_CAMERA_HOST_FINISHED =
            PACKAGE_NAME + ".ACTION_CAMERA_HOST_FINISHED";
    public static final String ACTION_CAMERA_HOST_READY =
            PACKAGE_NAME + ".ACTION_CAMERA_HOST_READY";

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SESSION_TOKEN = "session_token";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_SCORE = "score";
    public static final String EXTRA_ACTIVITY_TOKEN = "activity_token";

    public static final String MODE_ENROLL = "enroll";
    public static final String MODE_TEST = "test";
    public static final String MODE_UNLOCK = "unlock";

    private Constants() {
    }
}

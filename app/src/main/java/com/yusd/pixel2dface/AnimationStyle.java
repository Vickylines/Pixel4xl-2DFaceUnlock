package com.yusd.pixel2dface;

/** Stable persisted IDs shared by app settings, IPC and the SystemUI renderer. */
final class AnimationStyle {
    static final int FACE = 0, ISLAND = 1, MINIMAL = 4, FOCUS = 5;
    // IDs 2 and 3 belonged to removed styles. Keep them reserved for persisted settings.
    private static final int[] IDS = {FACE, ISLAND, MINIMAL, FOCUS};
    private static final String[] NAMES = {"面容光环", "灵动岛", "线条", "聚焦"};
    static final int COUNT = IDS.length;
    private AnimationStyle() { }

    static int at(int position) { return IDS[position]; }

    static int positionOf(int style) {
        for (int position = 0; position < IDS.length; position++) {
            if (IDS[position] == style) return position;
        }
        return 0;
    }

    static int normalize(int value) { return at(positionOf(value)); }
    static String name(int style) { return NAMES[positionOf(style)]; }
}

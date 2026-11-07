package com.yusd.pixel2dface;

/** Stable persisted IDs shared by app settings, IPC and the SystemUI renderer. */
final class AnimationStyle {
    static final int FACE = 0, ISLAND = 1, GLOW = 2, ORBIT = 3, MINIMAL = 4, DOTS = 5;
    static final int COUNT = 6;
    private static final String[] NAMES = {"面容光环", "灵动岛", "光幕", "星轨", "线条", "点阵"};
    private AnimationStyle() { }
    static int normalize(int value) { return value >= FACE && value < COUNT ? value : FACE; }
    static String name(int style) { return NAMES[style]; }
}

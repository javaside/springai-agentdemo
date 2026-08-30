package io.github.javaside.springai.codetui.ui.update;

public final class UiDirty {
    public static final int NONE = 0;
    public static final int OUTPUT = 1;
    public static final int VIEW = 1 << 1;
    public static final int CONTROL = 1 << 2;
    public static final int ALL = OUTPUT | VIEW | CONTROL;

    private UiDirty() {}

    public static boolean contains(int bits, int flag) {
        return (bits & flag) == flag;
    }
}

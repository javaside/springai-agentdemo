package io.github.javaside.springai.codetui.ui.update;

@FunctionalInterface
public interface UiChangeListener {
    void onUiChanged(int dirtyBits);

    static UiChangeListener noop() {
        return dirtyBits -> { };
    }
}

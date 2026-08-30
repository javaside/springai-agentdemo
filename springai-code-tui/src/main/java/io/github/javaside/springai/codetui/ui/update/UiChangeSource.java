package io.github.javaside.springai.codetui.ui.update;

public interface UiChangeSource {
    void setUiChangeListener(UiChangeListener listener);
    long uiVersion();
}

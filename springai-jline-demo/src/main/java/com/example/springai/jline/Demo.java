package com.example.springai.jline;

import org.jline.terminal.Terminal;

import java.io.IOException;

/**
 * 所有 JLine 组件演示场景的共享契约。
 * 放在根包，便于后续 linereader / completer 等组件包复用，避免反向依赖 terminal 包。
 */
public interface Demo {

    /** 菜单中显示的名称。 */
    String name();

    /** 一行说明，菜单高亮时展示。 */
    String description();

    /**
     * 运行场景。使用 launcher 传入的唯一 Terminal，禁止自行创建 Terminal。
     * 实现必须在 finally 中复原所有终端状态（原始模式 / 备用屏 / 光标 / 信号 handler）。
     */
    void run(Terminal terminal) throws IOException;
}

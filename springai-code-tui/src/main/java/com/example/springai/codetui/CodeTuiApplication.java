package com.example.springai.codetui;

import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;

public class CodeTuiApplication {
    public static void main(String[] args) throws Exception {
        ConversationState state = new ConversationState();
        // 骨架桩：SubmitHandler 由桩落 transcript（真 agent 时改由 onUserMessage 落，View 代码不变）
        CodeTuiView view = new CodeTuiView(state, text -> {
            state.appendLine("你> " + text);
            state.appendLine("（回显）AI> " + text);
            return null;   // 骨架无真回合
        });
        // 【被动重绘自检——里程碑1 地基验证，Task 8 前删除】后台线程隔 1s 追加一行，
        // 不碰键盘该行也应出现 → 证明 tickRate 周期重绘对「后台线程写状态」生效。
        Thread probe = new Thread(() -> {
            for (int i = 1; i <= 5; i++) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                state.appendLine("[被动重绘自检] tick #" + i + "（无按键即出现即通过）");
            }
        }, "passive-repaint-probe");
        probe.setDaemon(true);
        probe.start();
        view.run();
    }
}

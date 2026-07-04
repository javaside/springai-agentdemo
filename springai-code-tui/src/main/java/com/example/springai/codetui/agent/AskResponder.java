package com.example.springai.codetui.agent;

import java.util.Map;

/**
 * UI → 握手桥 的应答回调（纯 Java，不泄漏 Spring AI 类型）。
 * UI 答完调 {@link #answer}，键=问题文本、值=选中 label（多选逗号分隔 / 自由文本）；取消调 {@link #cancel}。
 * 二者都只会被调用一次，用于唤醒阻塞在桥里的工具线程。
 */
public interface AskResponder {
    void answer(Map<String, String> answers);
    void cancel();
}

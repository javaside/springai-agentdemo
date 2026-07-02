package com.example.springai.codetui.agent;

import reactor.core.Disposable;

/** 提交一次对话，返回可取消句柄。CodingAgent 实现它；骨架期用回显桩实现（返回 null）。 */
@FunctionalInterface
public interface SubmitHandler {
    Disposable submit(String text);
}

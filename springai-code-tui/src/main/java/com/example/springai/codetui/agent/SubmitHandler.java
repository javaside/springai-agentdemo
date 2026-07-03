package com.example.springai.codetui.agent;

import reactor.core.Disposable;

import java.util.List;

/** 提交一次对话，返回可取消句柄。CodingAgent 实现它；骨架期用回显桩实现（返回 null）。 */
public interface SubmitHandler {
    Disposable submit(String text);

    /**
     * 带指定技能提交：发送前先调用该技能工具、把正文注入到 text 前。
     * skillName 为 null 时等价 {@link #submit(String)}。默认实现委托无技能提交，便于桩省略。
     */
    default Disposable submit(String text, String skillName) { return submit(text); }

    // ── 模型选择（/model 选择器用；默认空实现，便于回显桩/测试桩省略） ──
    /** 可选模型列表。 */
    default List<ModelOption> models() { return List.of(); }

    /** 当前使用的模型 id。 */
    default String currentModel() { return ""; }

    /** 切换到指定模型 id（对后续回合生效）。 */
    default void selectModel(String id) { }

    /** 手动压缩会话历史（/compact）。默认空实现，便于回显桩/测试桩省略。 */
    default void compact() { }

    /** 当前会话上下文用量快照（/context）。默认空快照，便于回显桩/测试桩省略。 */
    default ContextStats contextStats() { return ContextStats.empty(); }

    /** 当前可用技能清单（/skills 展示）。默认空，便于回显桩/测试桩省略。 */
    default List<SkillInfo> skills() { return List.of(); }
}

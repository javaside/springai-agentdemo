package io.github.javaside.springai.codetui.agent;

import java.util.List;

/** 一个问题：文本 + header chip + 选项 + 是否多选（AskUserQuestion 的 Question 的纯 Java 镜像）。
 *
 * @param question    问题正文
 * @param header      面板上的短标签（chip）
 * @param options     可选项，至少一项（空选项会让面板按键除零）
 * @param multiSelect true = 可多选
 */
public record QuestionSpec(String question, String header, List<OptionSpec> options, boolean multiSelect) {
    public QuestionSpec {
        options = List.copyOf(options);   // 防御性拷贝，保持不可变
    }
}

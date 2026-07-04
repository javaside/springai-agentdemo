package com.example.springai.codetui.agent;

import java.util.List;

/** 一个问题：文本 + header chip + 选项 + 是否多选（AskUserQuestion 的 Question 的纯 Java 镜像）。 */
public record QuestionSpec(String question, String header, List<OptionSpec> options, boolean multiSelect) {
    public QuestionSpec {
        options = List.copyOf(options);   // 防御性拷贝，保持不可变
    }
}

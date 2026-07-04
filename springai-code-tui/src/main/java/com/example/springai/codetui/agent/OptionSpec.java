package com.example.springai.codetui.agent;

/** 一个选项：展示 label + 说明（AskUserQuestion 的 Option 的纯 Java 镜像，避免 Spring AI 类型泄漏进接缝）。 */
public record OptionSpec(String label, String description) {}

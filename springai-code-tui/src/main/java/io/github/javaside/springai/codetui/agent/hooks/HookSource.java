package io.github.javaside.springai.codetui.agent.hooks;

/** 钩子来源层：用户层（~/.codetui）无需信任；项目层（随仓库分发）需 hash 信任。 */
public enum HookSource { USER, PROJECT }

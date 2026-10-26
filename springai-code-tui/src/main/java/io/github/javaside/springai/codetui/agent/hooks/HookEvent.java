package io.github.javaside.springai.codetui.agent.hooks;

/** 四个生命周期事件（配置键用官方拼写，fromConfigKey 映射；未知键返回 null 由 loader 跳过）。 */
public enum HookEvent {
    PRE_TOOL_USE("PreToolUse"), POST_TOOL_USE("PostToolUse"), STOP("Stop"), SESSION_START("SessionStart");

    private final String configKey;
    HookEvent(String configKey) { this.configKey = configKey; }
    public String configKey() { return configKey; }

    static HookEvent fromConfigKey(String key) {
        for (HookEvent e : values()) {
            if (e.configKey.equals(key)) return e;
        }
        return null;
    }
}

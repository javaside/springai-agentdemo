package io.github.javaside.springai.codetui.agent.hooks;

import java.util.regex.Pattern;

/**
 * 一条钩子定义（loader 产出、不可变）。matcher 是工具名正则（空串=全部）；
 * <b>上界 120s 刻意远小于流空闲默认 300s</b>（spec 事实 #13）——钩子在工具线程同步静默，
 * 逼近空闲判定会自造「流重试→工具重跑→钩子再跑」循环。
 */
public record HookDefinition(String matcher, String command, int timeoutSeconds,
                             HookEvent event, HookSource source) {

    /** STOP/SESSION_START 无工具名概念（matcher 被忽略），恒匹配；工具事件按正则整段匹配。 */
    public boolean matchesTool(String toolName) {
        if (event == HookEvent.STOP || event == HookEvent.SESSION_START) return true;
        if (matcher == null || matcher.isEmpty()) return true;
        if (toolName == null) return false;   // 工具事件给了 matcher 却拿不到工具名：不匹配比误全跑安全
        return Pattern.matches(matcher, toolName);
    }
}

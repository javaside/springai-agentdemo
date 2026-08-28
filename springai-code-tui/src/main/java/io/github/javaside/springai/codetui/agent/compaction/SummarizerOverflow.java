package io.github.javaside.springai.codetui.agent.compaction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 摘要请求失败的「超限」判定与真实窗口数字解析。
 *
 * <p>分类遍历异常 cause chain(Spring AI 通常把服务端 4xx 包在通用 RuntimeException 里),任一层消息
 * 命中超限特征串才算超限;其余(网络/限流/鉴权)一律不算——<b>绝不拿网络错误调预算</b>。
 *
 * <p>数字解析<b>锚定窗口值</b>而非「找第一个数字」:Anthropic 的 {@code 195300 tokens > 200000 maximum}
 * 里 195300 是请求量,取错就把预算钉在还是会失败的水平上。
 */
final class SummarizerOverflow {

    /** cause chain 遍历深度上限(防环)。 */
    private static final int MAX_CAUSE_DEPTH = 8;

    private static final String[] OVERFLOW_MARKERS = {
            "context_length_exceeded", "prompt is too long", "maximum context length",
            "input too long", "context window", "最大上下文", "上下文长度", "输入过长",
    };

    /** 锚定窗口数字的模式,按序尝试;第 1 捕获组即窗口值(允许千分位逗号)。 */
    private static final Pattern[] WINDOW_PATTERNS = {
            // OpenAI / DeepSeek: "maximum context length is 163857 tokens"
            Pattern.compile("maximum context length is\\s*([0-9][0-9,]*)", Pattern.CASE_INSENSITIVE),
            // Anthropic: "195300 tokens > 200000 maximum"
            Pattern.compile(">\\s*([0-9][0-9,]*)\\s*maximum", Pattern.CASE_INSENSITIVE),
            // 中文变体: "最大上下文长度为 131072" / "最长 131072"
            Pattern.compile("(?:最大上下文(?:长度)?为?|最长)\\s*([0-9][0-9,]*)"),
    };

    private SummarizerOverflow() { }

    static boolean isOverflow(Throwable failure) {
        return firstMessageMatching(failure) != null;
    }

    /** 从 cause chain 里解析锚定的窗口 token 数;解析不到返回 null(调用方走减半探测)。 */
    static Long parseWindowTokens(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            for (Pattern pattern : WINDOW_PATTERNS) {
                Matcher matcher = pattern.matcher(message);
                if (matcher.find()) {
                    try {
                        return Long.parseLong(matcher.group(1).replace(",", ""));
                    } catch (NumberFormatException ignored) {
                        // 长到溢出的数字视为没解析到,继续找
                    }
                }
            }
        }
        return null;
    }

    private static String firstMessageMatching(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            for (String marker : OVERFLOW_MARKERS) {
                if (lower.contains(marker)) return message;
            }
        }
        return null;
    }
}

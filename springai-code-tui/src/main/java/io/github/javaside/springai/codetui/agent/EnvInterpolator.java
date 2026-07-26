package io.github.javaside.springai.codetui.agent;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ${VAR}} 插值：把字符串里的占位符替换成解析器给出的值。
 *
 * <p>用于 {@code mcp.json} 的 headers 值——token 留在环境变量 / {@code ~/.secrets}，配置文件只写引用。
 * 便于多机共用同一份配置，也避免 token 明文散落在配置文件里。
 *
 * <p><b>解析器可注入</b>（生产传 {@code System::getenv}、测试传假 map），故本类不直接读环境变量。
 */
public final class EnvInterpolator {

    /**
     * 引用了未定义变量。<b>抛而不是静默留下字面量</b>：带着 {@code ${TOKEN}} 去请求只会拿到一个
     * 看不懂的 401，排查成本远高于在解析期就报出变量名。
     */
    public static final class UndefinedVariableException extends RuntimeException {

        private final String variable;

        public UndefinedVariableException(String variable) {
            super("引用了未定义的环境变量 " + variable);
            this.variable = variable;
        }

        public String variable() {
            return variable;
        }
    }

    /** 变量名沿用 shell 惯例：字母或下划线开头，后接字母数字下划线。 */
    private static final Pattern VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvInterpolator() {
    }

    public static String interpolate(String raw, Function<String, String> resolver) {
        if (raw == null || !raw.contains("${")) {
            return raw;
        }
        Matcher matcher = VAR.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = resolver.apply(name);
            if (value == null) {
                throw new UndefinedVariableException(name);
            }
            // quoteReplacement：替换值里的 $ 与 \ 必须按字面处理，否则会被当成组引用/转义符
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

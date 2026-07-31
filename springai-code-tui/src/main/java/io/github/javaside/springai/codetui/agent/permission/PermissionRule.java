package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * 一条权限规则。DSL 形如 {@code 工具名(内容模式)}，三种形态按以下顺序判别：
 *
 * <ol>
 *   <li>{@code 工具名} 或 {@code 工具名(*)} → 匹配该工具<b>全部</b>调用（{@code pattern == null}）；</li>
 *   <li>{@code 工具名(字面量:*)} → <b>前缀匹配</b>：{@code :} 之前按原样比较，<b>不再解释任何通配符</b>
 *       （故 {@code Bash(rm -rf /:*)} 明确是「以 rm -rf / 开头的命令」，不会被误读成路径 glob）；</li>
 *   <li>其余 → 目标是路径时按 <b>glob</b>（{@code *} 单层、{@code **} 递归），否则<b>整串相等</b>。</li>
 * </ol>
 *
 * <p>「目标是不是路径」由调用方（{@link ToolRegistry.Entry#pathTarget()}）给定，<b>不</b>由类别推断——
 * {@code BashOutput} 是 READ_ONLY 但目标是 {@code bash_id}，不是路径。
 *
 * @param toolName 工具注册名；{@code "*"} 匹配任意工具
 * @param pattern  内容模式；{@code null} = 该工具全部调用
 * @param behavior 命中后的结论
 * @param scope    存放层（决定「允许，且别再问」写到哪）
 */
public record PermissionRule(String toolName, String pattern,
                             PermissionBehavior behavior, RuleScope scope) {

    /** 解析 DSL；非法返回 {@code null}（调用方记 WARN 跳过，<b>绝不抛异常</b>）。 */
    public static PermissionRule parse(String dsl, PermissionBehavior behavior, RuleScope scope) {
        if (dsl == null) {
            return null;
        }
        String s = dsl.trim();
        if (s.isEmpty()) {
            return null;
        }
        int open = s.indexOf('(');
        if (open < 0) {
            return new PermissionRule(s, null, behavior, scope);   // 裸工具名 = 全部调用
        }
        if (!s.endsWith(")")) {
            return null;                                            // 括号不闭合
        }
        String tool = s.substring(0, open).trim();
        if (tool.isEmpty()) {
            return null;
        }
        String pat = s.substring(open + 1, s.length() - 1).trim();
        if (pat.isEmpty() || "*".equals(pat)) {
            return new PermissionRule(tool, null, behavior, scope);
        }
        return new PermissionRule(tool, pat, behavior, scope);
    }

    /** 还原成 DSL（供「允许，永久」回写；无 pattern 统一写成 {@code 工具名(*)}）。 */
    public String toDsl() {
        return pattern == null ? toolName + "(*)" : toolName + "(" + pattern + ")";
    }

    /**
     * 是否命中一次调用。
     *
     * @param callTool   本次调用的工具注册名
     * @param target     判定目标（路径 / 命令 / bash_id / 整串入参 JSON），可为 null
     * @param pathTarget {@code target} 是否是文件路径
     * @param root       项目根（相对 glob 对绝对目标时用它相对化）
     */
    public boolean matches(String callTool, String target, boolean pathTarget, Path root) {
        if (!"*".equals(toolName) && !toolName.equals(callTool)) {
            return false;
        }
        if (pattern == null) {
            return true;
        }
        if (target == null) {
            return false;
        }
        if (pattern.endsWith(":*")) {
            return target.startsWith(pattern.substring(0, pattern.length() - 2));
        }
        if (pathTarget) {
            return globMatches(pattern, target, root);
        }
        return pattern.equals(target);
    }

    /**
     * 路径 glob 匹配。先按原样比一次；若模式是相对的而目标是 root 内的绝对路径，
     * 则用 {@code root.relativize} 后再比一次（使 {@code Read(src/*.java)} 对绝对路径也成立）。
     *
     * <p>任何解析异常（{@code InvalidPathException} / {@code PatternSyntaxException}）
     * 一律降级为「不命中」，绝不外抛——规则文件是用户手写的，非法模式不该炸掉一次工具调用。
     */
    static boolean globMatches(String pattern, String target, Path root) {
        try {
            String p = pattern.startsWith("~/")
                    ? System.getProperty("user.home") + pattern.substring(1)
                    : pattern;
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + p);
            Path t = Path.of(target).normalize();
            if (m.matches(t)) {
                return true;
            }
            if (!p.startsWith("/") && root != null && t.isAbsolute() && t.startsWith(root)) {
                return m.matches(root.relativize(t));
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

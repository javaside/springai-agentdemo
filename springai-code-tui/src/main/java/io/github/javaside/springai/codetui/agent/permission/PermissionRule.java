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
 * <p><b>匹配契约（已知边界，调用方必须知道）：</b>
 * <ul>
 *   <li><b>前缀规则只对单段命令生效</b>：目标含 {@code ; | &}、换行（含 {@code \r}），
 *       或 {@link #hasUnsplittableConstruct} 认定的不可拆分构造（<code>` $( &lt;( &gt;( ${</code>）
 *       时一律不命中，
 *       落回 ASK。命令是否需要按段授权由 {@link PermissionEngine} 负责（COMMAND 类别先拆分再逐段判定）。</li>
 *   <li><b>不做空白归一</b>：{@code rm  -rf /}（双空格）、{@code  rm -rf /}（前导空格）
 *       不命中 {@code Bash(rm -rf /:*)}。故 <b>DSL 的 deny 是尽力而为，真正的护栏是
 *       {@code DangerousPaths} 的内置检查</b>（它在决策顺序里位于 allow 规则之前，不可绕过）。</li>
 *   <li><b>路径匹配区分大小写</b>：{@code /etc/**} 不命中 {@code /ETC/passwd}，
 *       而 macOS 的 APFS 默认大小写不敏感、二者是同一文件。deny 方向会漏。</li>
 *   <li><b>只 {@code normalize()} 不 {@code toRealPath()}</b>：符号链接不解析
 *       （{@code toRealPath} 会让「写一个尚不存在的文件」直接失败）。
 *       <b>由 {@code ToolTargets} 负责交出已规范化的路径</b>。</li>
 *   <li><b>上面两条只是<i>本类</i>的语义，不是系统的最终行为</b>：{@link PermissionEngine}
 *       在其上叠了一层——deny / ask 方向会拿<b>折叠孪生</b>（pattern 与目标一起折小写，
 *       相对 pattern 连 root 一起折）和 {@link PathAliases} 给出的<b>符号链接解析后</b>写法
 *       各重试一遍，故这两个洞在 deny / ask 方向上已经补掉；
 *       <b>allow 方向刻意不重试</b>，本类的精确语义就是它的全部语义。
 *       这个不对称是有意的：放宽 deny 只是多拦一次，放宽 allow 不可逆。
 *       实现与理由见 {@code PermissionEngine.denyOrAskMatches} 及该类注释。</li>
 *   <li><b>{@code :*} 前缀语义只对命令生效</b>：路径目标上的 {@code :*} 规则
 *       （如 {@code Write(/etc/:*)}）<b>恒不命中</b>——写成 {@code Write(/etc/**)}。
 *       {@link PermissionEngine} 载入此类规则时须记 WARN，否则是一条静默失效的 deny。</li>
 *   <li><b>前缀停在分隔符上时匹配任意续接</b>：{@code Bash(rm -rf /tmp/:*)} 并非
 *       「限于 /tmp 内」的授权，它同样命中 {@code rm -rf /tmp/../home/user}。
 *       手写此类规则请让前缀停在完整词上；自动生成的规则由
 *       {@link PermissionEngine} 保证不会停在非字母数字字符上。</li>
 * </ul>
 *
 * @param toolName 工具注册名；{@code "*"} 匹配任意工具
 * @param pattern  内容模式；{@code null} = 该工具全部调用
 * @param behavior 命中后的结论
 * @param scope    存放层（决定「允许，且别再问」写到哪）
 */
public record PermissionRule(String toolName, String pattern,
                             PermissionBehavior behavior, RuleScope scope) {

    /** 解析 DSL；非法返回 {@code null}（调用方记 WARN 跳过，<b>绝不抛异常</b>）。
     *
     * @param dsl      规则 DSL，形如 {@code Bash(git status:*)}
     * @param behavior 命中后的结论
     * @param scope    存放层
     * @return 解析出的规则；DSL 非法时为 null
     */
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
        if (pat.isEmpty()) {
            return null;                                // I2：空括号是非法 DSL，不是「全部」
        }
        if ("*".equals(pat)) {
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
        return matches(callTool, target, pathTarget, root, true);
    }

    /**
     * 同上，但由调用方声明「目标有没有可能是一条 shell 命令」。
     *
     * <p><b>为什么需要这个开关</b>：{@link #hasShellSeparator} 那道守卫是<b>命令专用</b>的
     * （防「多段命令被首段的前缀规则整条放行」），可它此前无条件作用于所有非路径目标。
     * 而 {@code NETWORK_READ} 的域名前缀规则（{@code WebFetch(https://docs.spring.io/:*)}）
     * 目标是 URL，查询串里的 {@code &} 是再正常不过的字符——
     * {@code https://x/?a=1&b=2} 会被这道守卫判成「多段命令」而不命中。
     * allow 方向只是白问一次，<b>deny 方向却是实打实的绕过</b>：
     * 一条 {@code deny:WebFetch(https://evil.com/:*)} 加个 {@code ?a=1&b=2} 就躲开了。
     *
     * @param callTool           本次调用的工具注册名
     * @param target             判定目标（路径已 normalize；非路径目标是原串）
     * @param pathTarget         目标是否为路径，决定走 glob 还是整串相等
     * @param root               项目根目录，用于相对 pattern 的匹配
     * @param separatorSensitive 目标可能是 shell 命令（COMMAND 类别、以及判定目标是整串入参的
     *                           UNKNOWN / MCP 工具）时传 true，保留守卫；
     *                           url / query / bash_id 这类已知非命令的目标传 false。
     *                           四参重载一律 true——默认保守。
     * @return 是否命中本规则
     */
    public boolean matches(String callTool, String target, boolean pathTarget, Path root,
                           boolean separatorSensitive) {
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
            if (pathTarget) {
                return false;                           // I4：路径目标不吃前缀语义
            }
            if (separatorSensitive && hasShellSeparator(target)) {
                return false;                           // C1：多段命令一律不吃前缀规则
            }
            String prefix = pattern.substring(0, pattern.length() - 2).trim();
            if (prefix.isEmpty()) {
                return false;                           // I2：空前缀不放行一切
            }
            return matchesPrefix(target, prefix);        // I3：词边界（分隔符即边界，见 matchesPrefix）
        }
        if (pathTarget) {
            return globMatches(pattern, target, root);
        }
        return pattern.equals(target);
    }

    /**
     * 前缀是否在<b>词边界</b>上命中目标。
     *
     * <p>{@code ls} 停在字母上 → 必须整串相等或后接空格，故不授权 {@code lsof}；
     * {@code rm -rf /} 停在 {@code /} 上 → 该分隔符本身就是边界，
     * 可直接接续，故仍命中 {@code rm -rf /var/tmp/x}。
     */
    static boolean matchesPrefix(String target, String prefix) {
        if (!target.startsWith(prefix)) {
            return false;
        }
        if (target.length() == prefix.length()) {
            return true;                                 // 整串相等
        }
        char last = prefix.charAt(prefix.length() - 1);
        if (!Character.isLetterOrDigit(last) && last != '_') {
            return true;                                 // 前缀已停在分隔符上
        }
        return target.charAt(prefix.length()) == ' ';    // 否则必须落在空格上
    }

    /**
     * 是否含<b>无法可靠拆分</b>的 shell 结构（命令替换 {@code $(} / 算术展开 {@code $((} /
     * 反引号 / 进程替换 {@code <( >(} / {@code ${...}} 展开）。
     *
     * <p><b>这是本项目对「不可拆分构造」的唯一定义</b>，{@link #hasShellSeparator} 与
     * {@link BashCommandSplitter} 都必须走这里。此前两处各维护一份字符清单，
     * 已漂移出 {@code ${} 一处分歧——而分歧的危险方向是固定的：
     * 更严格的组件跑在决策顺序更后面（allow 规则第 5 步、命令拆分第 6 步），
     * 于是永远轮不到它。清单只能有一份。
     *
     * <p>{@code $(} 顺带覆盖了算术展开 {@code $((...))}；{@code ${} 单列，
     * 因为 bash 的 {@code ${x@P}} 会对取到的值再做一轮提示符展开（可执行命令替换）。
     * 裸 {@code $VAR} 不在此列：实测其展开结果不会被重新解析出分隔符。
     */
    static boolean hasUnsplittableConstruct(String c) {
        return c.contains("$(") || c.indexOf('`') >= 0
                || c.contains("<(") || c.contains(">(")
                || c.contains("${");
    }

    /**
     * 命令是否含「会引出另一条命令」或「无法可靠拆分」的 shell 结构。
     * 含则前缀规则一律不命中（失败关闭）。
     *
     * <p>两份清单都<b>不</b>在这里定义，本方法只是把它们并起来：
     * 分隔符归 {@link BashCommandSplitter#isSeparatorChar}，
     * 不可拆分构造归 {@link #hasUnsplittableConstruct}。
     * 分隔符那份正是制造过 C1 与 {@code <(} 两个 Critical 的清单，
     * 曾在此处与 splitter 各存一份（内容碰巧相等，故无 exploit）——
     * 漂移方向是固定的：只往 splitter 那侧加字符，这里仍判「简单」，
     * 第 5 步放行后第 6 步永远不会跑。{@code SeparatorSetConsistencyTest} 遍历字符空间钉住它。
     *
     * <p><b>不</b>认<b>普通</b>重定向 {@code > <}——它不引出新命令，
     * 其落点风险由 {@code DangerousPaths} 负责。
     * 但 {@code <(} / {@code >(} 是<b>进程替换</b>，恰是「重定向不引出命令」不成立的那一种，
     * 故已由不可拆分构造那份清单拦下。
     */
    static boolean hasShellSeparator(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (BashCommandSplitter.isSeparatorChar(command.charAt(i))) {
                return true;
            }
        }
        return hasUnsplittableConstruct(command);
    }

    /**
     * 把字面路径转义成只匹配它自己的 glob（供「允许，永久」按具体文件生成规则）。
     *
     * <p>不转义会出事：{@code report[2026].md} 里的 {@code [2026]} 被 glob 读成字符类，
     * 生成的规则<b>永不命中被批准的那个文件</b>，反而放开了 {@code report0/2/6.md}。
     *
     * @param literal 字面路径
     * @return 只匹配该路径本身的 glob pattern
     */
    public static String escapeGlob(String literal) {
        StringBuilder sb = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' || c == '*' || c == '?' || c == '['
                    || c == ']' || c == '{' || c == '}') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
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

package io.github.javaside.springai.codetui.agent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bash 命令的分段与只读判定。
 *
 * <p><b>本类的安全底线是「拆不动就问」</b>：解析器只要不确定语义就返回
 * {@code parseable=false}，引擎据此直接 ASK——宁可烦用户一次，绝不放行未知语义。
 * 触发条件：命令替换 {@code $(...)}、算术展开 {@code $((...))}、反引号、
 * 进程替换 {@code <(...)} / {@code >(...)}、花括号展开 {@code ${...}}、
 * 引号内出现分隔符、引号不配对。
 *
 * <p><b>与 {@link PermissionRule#hasShellSeparator} 的一致性契约</b>：本类拆出的任何一段，
 * 都不得再被 {@code hasShellSeparator} 判为复合命令——否则前缀规则（跑在决策顺序更前面、
 * 且更宽松）与本类会对「什么是一条命令」给出不同答案，那是可利用的空子。
 * 由此 {@code \n} / {@code \r} 必须与 {@code ; | &} 同等对待：
 * 实测 {@code bash -c 'echo hi\nrm -rf /'} 会执行第二条命令，若当作单段，
 * 首词 {@code echo} 命中只读白名单就把 {@code rm -rf /} 一起放行了。
 *
 * <p><b>白名单是「首词能不能定性整段」的判断，不是「这个程序平时危不危险」</b>。
 * 故以下实测有逃逸口的命令<b>不</b>在白名单里：{@code find}（{@code -delete} / {@code -exec}）、
 * {@code rg}（{@code --pre} / {@code --hostname-bin} 执行外部程序）、
 * {@code env}（本身就是命令启动器）；{@code git branch -D} 删分支、{@code git remote add} 改配置、
 * {@code git diff --output} 落盘，故 git 子命令白名单另做选项过滤。
 * 同理，任何带重定向的段一律不算只读——{@code echo x > ~/.zshrc} 的首词是 {@code echo}，
 * 但它落盘。重定向落点的风险由 {@code DangerousPaths} 兜底，本类只负责不把它谎报成只读。
 */
public final class BashCommandSplitter {

    /**
     * 分段结果。
     *
     * @param segments  按 {@code && || ; | &} 与换行拆出的各段（已 trim，空段丢弃）
     * @param parseable 是否有把握完整理解这条命令；false 时调用方必须 ASK，不得据 segments 放行
     */
    public record Split(List<String> segments, boolean parseable) {
    }

    /**
     * 只读命令白名单（首个词）。
     *
     * <p>入选标准：<b>光看首词就能断定整段无副作用</b>。凡是靠选项能执行外部程序或删文件的
     * （{@code find} / {@code rg} / {@code env} 等）一律不收，宁可多问一次。
     */
    private static final Set<String> READ_ONLY = Set.of(
            "ls", "cat", "head", "tail", "grep", "wc", "pwd", "echo",
            "which", "file", "stat", "du", "df", "date", "whoami", "uname", "printenv",
            "cmp", "basename", "dirname", "realpath");

    /** git 的只读子命令。 */
    private static final Set<String> GIT_READ_ONLY = Set.of(
            "status", "diff", "log", "show", "branch", "remote", "blame", "describe", "rev-parse");

    /**
     * git 只读子命令下仍会产生副作用的选项，命中即不算只读。
     *
     * <p>{@code branch}/{@code remote} 的写操作靠位置参数区分（{@code git branch -D x}、
     * {@code git remote add}），故另在 {@link #isGitReadOnly} 里按子命令单独挡。
     */
    private static final Set<String> GIT_WRITE_OPTIONS = Set.of(
            "-D", "-d", "--delete", "-m", "-M", "--move", "-c", "-C", "--copy",
            "--edit-description", "--set-upstream-to", "-u", "--unset-upstream", "-f", "--force");

    /** {@code branch} / {@code remote} 的写子命令（位置参数形态）。 */
    private static final Set<String> GIT_WRITE_SUBCOMMANDS = Set.of(
            "add", "remove", "rm", "rename", "set-url", "set-head", "set-branches", "prune", "update");

    /** {@code ACCEPT_EDITS} 下额外放行的文件系统写命令。<b>刻意不含 rm</b>。 */
    private static final Set<String> FS_WRITE = Set.of("mkdir", "touch", "mv", "cp");

    private BashCommandSplitter() {
    }

    /** 按 {@code && || ; | &} 与换行拆段，同时判定「拆不动就问」。 */
    public static Split split(String command) {
        List<String> segs = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return new Split(List.of(), true);
        }
        if (hasUnparseableConstruct(command)) {
            return new Split(List.of(), false);
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (isSeparatorChar(c)) {
                    return new Split(List.of(), false);   // 引号内含分隔符：语义不明，退回人工
                }
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (isSeparatorChar(c)) {
                // && / || 吃掉第二个字符；单个 &（后台执行）与换行同样是分隔
                if (i + 1 < command.length() && command.charAt(i + 1) == c) {
                    i++;
                }
                addSegment(segs, cur);
                continue;
            }
            cur.append(c);
        }
        if (quote != 0) {
            return new Split(List.of(), false);           // 引号不配对
        }
        addSegment(segs, cur);
        return new Split(List.copyOf(segs), true);
    }

    /**
     * 单段是否只读（按首个词查白名单；git 另查子命令）。
     *
     * <p>先自查一遍复合结构与重定向：调用方理论上已拆过段，但本方法是 public 的安全判定，
     * 不能把「调用方拆对了」当前提——漏一次就是把整条复合命令按首词放行。
     */
    public static boolean isReadOnly(String segment) {
        if (!isSingleSideEffectFreeSegment(segment)) {
            return false;
        }
        String head = firstWord(segment);
        if (head.isEmpty()) {
            return false;
        }
        if ("git".equals(head)) {
            return isGitReadOnly(segment);
        }
        if ("java".equals(head) || "mvn".equals(head)) {
            // 只放行版本查询，别的（mvn test / java -jar）都会跑代码
            String second = secondWord(segment);
            return "-version".equals(second) || "--version".equals(second) || "-v".equals(second);
        }
        return READ_ONLY.contains(head);
    }

    /** 单段是否属于 {@code ACCEPT_EDITS} 下额外放行的文件系统写。 */
    public static boolean isFileSystemWrite(String segment) {
        if (!isSingleSideEffectFreeSegment(segment)) {
            return false;
        }
        return FS_WRITE.contains(firstWord(segment));
    }

    /**
     * 段是否「单一、且副作用范围就等于首词语义」——不含复合结构、不含重定向。
     *
     * <p>重定向不引出新命令，所以它不算分隔符（{@link PermissionRule#hasShellSeparator} 的既有约定），
     * 但它<b>确实</b>让 {@code echo} 这类只读命令落盘，故不能算进只读 / 自动放行白名单。
     */
    private static boolean isSingleSideEffectFreeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        if (PermissionRule.hasShellSeparator(segment)) {
            return false;
        }
        if (hasUnparseableConstruct(segment)) {
            return false;
        }
        return !hasUnquotedRedirection(segment);
    }

    /** 段内是否有引号外的 {@code >} / {@code <}（引号里的 {@code grep 'a>b'} 不算）。 */
    private static boolean hasUnquotedRedirection(String segment) {
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '>' || c == '<') {
                return true;
            }
        }
        return false;
    }

    /** git 段是否只读：子命令在白名单内，且不带会写的选项 / 位置参数。 */
    private static boolean isGitReadOnly(String segment) {
        String sub = secondWord(segment);
        if (!GIT_READ_ONLY.contains(sub)) {
            return false;
        }
        List<String> args = words(segment);
        for (int i = 2; i < args.size(); i++) {
            String a = args.get(i);
            if (a.startsWith("--output")) {
                return false;                            // git diff/log --output=F 会落盘
            }
            if (GIT_WRITE_OPTIONS.contains(a)) {
                return false;
            }
            if (("branch".equals(sub) || "remote".equals(sub)) && GIT_WRITE_SUBCOMMANDS.contains(a)) {
                return false;                            // git remote add / branch rename
            }
        }
        return true;
    }

    /**
     * 是否含无法可靠拆分的结构。
     *
     * <p>{@code $(} 顺带覆盖了算术展开 {@code $((...))}；{@code ${} 单列，
     * 因为 bash 的 {@code ${x@P}} 会对取到的值再做一轮提示符展开（可执行命令替换）。
     * 裸 {@code $VAR} 不在此列：实测其展开结果不会被重新解析出分隔符。
     */
    private static boolean hasUnparseableConstruct(String c) {
        return c.contains("$(") || c.indexOf('`') >= 0
                || c.contains("<(") || c.contains(">(")
                || c.contains("${");
    }

    /** 分隔符：换行 / 回车与 {@code ; | &} 同级（实测 bash 的换行就是命令分隔）。 */
    private static boolean isSeparatorChar(char c) {
        return c == ';' || c == '|' || c == '&' || c == '\n' || c == '\r';
    }

    private static void addSegment(List<String> segs, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            segs.add(s);
        }
        cur.setLength(0);
    }

    /** 按空白切词（tab 实测同样是词分隔符）。 */
    private static List<String> words(String segment) {
        String s = segment == null ? "" : segment.trim();
        if (s.isEmpty()) {
            return List.of();
        }
        return List.of(s.split("\\s+"));
    }

    private static String firstWord(String segment) {
        List<String> w = words(segment);
        return w.isEmpty() ? "" : w.get(0);
    }

    private static String secondWord(String segment) {
        List<String> w = words(segment);
        return w.size() < 2 ? "" : w.get(1);
    }
}

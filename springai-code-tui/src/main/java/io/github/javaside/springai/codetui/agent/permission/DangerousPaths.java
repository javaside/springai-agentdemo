package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 内置不可绕过检查：<b>排在 allow 规则之前，任何 allow 规则盖不住，BYPASS 模式下也照样触发</b>。
 *
 * <p>命中后引擎强制 <b>ASK 而非 DENY</b>——本层是护栏不是牢笼，人确认了就该能做。
 * 正因为结论是「问一次」而不是「不许」，本层的取舍一律偏严：
 * <b>误报的代价是用户多按一次键，漏报的代价是密钥泄露或磁盘清空</b>。
 *
 * <p>每个方法返回<b>人话原因</b>（直接显示在审批面板上）或 {@code null}（未命中）。
 *
 * <h2>为什么按「路径结构」匹配，而不是按「家目录前缀」</h2>
 * 判据是<b>路径里有没有名为 {@code .ssh} 的一段</b>，而不是 {@code startsWith(home + "/.ssh")}。
 * 后者有三个已实测的漏法，且都不是靠 {@code toRealPath()} 能补的：
 * <ol>
 *   <li>macOS 的 firmlink：{@code /System/Volumes/Data/Users/zxh/.ssh/id_rsa} 与
 *       {@code ~/.ssh/id_rsa} 是同一份文件，但 {@code toRealPath()} 实测<b>不会</b>把前者收敛成后者
 *       （两者各自 real 到自己）；</li>
 *   <li>{@code ~} 刻意不展开（{@code ToolTargets} 的契约），与 root 解析组合后
 *       {@code ~/.ssh/id_rsa} 变成 {@code <root>/~/.ssh/id_rsa}——前缀比较永不命中；</li>
 *   <li>别人的家目录（{@code /Users/someone/.ssh/id_rsa}）本来也不该随便读写。</li>
 * </ol>
 * 结构匹配一次盖住三种，且不需要访问文件系统（判定必须对「尚不存在的文件」也成立）。
 *
 * <h2>为什么恒按大小写不敏感比较</h2>
 * macOS 的 APFS 默认大小写不敏感：{@code Write {"filePath":"<root>/.ENV"}} 实测写的就是 {@code .env}，
 * 而 {@code **}{@code /.env} 这样的模式匹配不上 {@code .ENV}。{@code .GIT/config}、{@code .SSH/} 同理。
 *
 * <p>计划原文建议「启动时探一次文件系统敏感性，敏感盘上保持原样比较」。<b>这里刻意不那么做</b>：
 * 探测只换来「大小写敏感盘上 {@code /etc/PASSWD} 不多问一次」，却引入一个新的失效模式——
 * 探测结果一旦判错，这层不可绕过的护栏就被静默削弱。本层结论是 ASK 不是 DENY，这笔交易是单向的。
 *
 * <p><b>只在本类做不敏感比较</b>，不改 {@link PermissionRule} 的通用 glob 语义：
 * 用户手写的规则应当保持可预期，而「不可绕过的内置护栏」才是必须堵死的那层。
 *
 * <h2>已知盲区（都是实测出来的，刻意留着，别当成 bug）</h2>
 * 大部分盲区的共同点是：<b>危险性不在命令的参数里</b>，本层看不出来，只能靠
 * 「非只读命令默认 ASK」兜。危险方向是固定的——<b>一旦有人写下宽松的 allow 规则、
 * 或切进 BYPASS，这些就没人拦了</b>。
 * <ul>
 *   <li><b>目标是 cwd 而非显式路径</b>：{@code cd / && rm -rf *} 的删除目标只是 {@code *}，
 *       危险性来自前一段的 {@code cd}。本层逐段判定，不跟踪段间的 cwd 变化。</li>
 *   <li><b>整目录扫描</b>：{@code grep -R AKIA ~} 拿整个家目录当输入，目标本身不是密钥路径。
 *       注意 {@code grep} <b>在只读白名单里</b>，故一条 {@code allow:Bash(grep:*)} 就能放它过去。</li>
 *   <li><b>取内容不经文件路径</b>：{@code git show HEAD:secrets.txt}、
 *       {@code git config --get user.password} 从对象库 / 配置里取值，参数不是路径。</li>
 *   <li><b>别的破坏手段</b>：{@code find / -delete}、{@code find / -exec rm {} +}、
 *       {@code git clean -xfd}、{@code chmod -R 777 /}、{@code mkfs}、{@code shred}、
 *       {@code truncate}、{@code xargs rm -rf < list}。删除类只认
 *       {@link #DELETE_COMMANDS} 里那几个命令名。</li>
 *   <li><b>家目录内的 PATH 目录</b>：{@code install -m 755 evil ~/.local/bin/git} 落在家目录内，
 *       故过得了 {@link #WRITABLE_ROOTS} 那道兜底，而它劫持之后的每一次 {@code git}。
 *       没收的理由是 {@code ~/.local} / {@code ~/bin} 也是日常产物落点，
 *       收进来会把审批疲劳推到「一律选永久允许」——那比这个洞更危险。</li>
 *   <li><b>读方向没有对应的白名单兜底</b>：{@code cat /etc/shadow}、
 *       {@code <root>/secrets.yaml}、{@code credentials.json} 都过。写方向能按
 *       {@link #WRITABLE_ROOTS} 兜是因为「该往哪写」可穷举，读不行——
 *       agent 读 {@code /usr/lib}、{@code /etc/hosts} 都是正常的，
 *       给读加白名单等于每次读系统文件都弹窗。</li>
 *   <li><b>不解析 shell 变量</b>：除 {@code $HOME} 外，变量目标在删除类命令里按「无法核实」
 *       处理（宁可多问），其他位置不追踪。</li>
 * </ul>
 *
 * <p><b>这些盲区不该靠往清单里加词来补</b>——本项目已有三次「黑名单在没人列到的那一项上失守」的记录。
 * 真正的兜底是决策顺序：非只读命令一律 ASK。本层只负责把<b>能结构化判定</b>的那部分咬死。
 */
public final class DangerousPaths {

    /** 路径里出现这一段就算敏感目录（任意层级，凭据/密钥所在）。 */
    private static final Set<String> SENSITIVE_DIR_SEGMENTS =
            Set.of(".ssh", ".aws", ".kube", ".gnupg", ".docker", "keychains");

    /**
     * <b>整个目录都是秘密</b>的那几个——读里面任何一个文件都要问。
     *
     * <p>与 {@link #SENSITIVE_DIR_SEGMENTS} 的区别在读方向：{@code ~/.ssh} 里有
     * {@code known_hosts} / {@code config} 这类读了无妨的文件（计划明确要求它们不触发），
     * 故 {@code .ssh} 不整目录收进来，改由 {@link #SSH_PUBLIC_FILES} 反向豁免。
     * 而 {@code .gnupg} / Keychains / {@code .docker} 里没有「读了无妨」的文件
     * （{@code ~/.docker/config.json} 存 registry 的 auth token）。
     */
    private static final Set<String> SECRET_STORE_DIRS =
            Set.of(".gnupg", ".aws", "keychains", ".docker");

    /**
     * {@code ~/.ssh} 里读了无妨的文件名——<b>白名单，不是黑名单</b>。
     *
     * <p>此前这里是按 {@code id_} 前缀判私钥，那是错的极性：{@code ssh-keygen -f ~/.ssh/deploy}
     * 生成的私钥就叫 {@code deploy}，{@code ~/.ssh/identity}（SSH1 默认名）同样漏。
     * 私钥的命名不可穷举，而「读了无妨」的那几个可以，故按后者反向豁免。
     * 代价是 {@code ~/.ssh/rc} 之类冷门文件会多问一次——本层是 ASK 不是 DENY，方向可接受。
     */
    private static final Set<String> SSH_PUBLIC_FILES = Set.of(
            "known_hosts", "known_hosts2", "config", "authorized_keys");
    // environment 曾在此列，已移出：sshd 会读它来给会话设环境变量，实践中有人往里放 token。

    /** 写进去就可能被劫持的配置文件名（任意层级——名叫 {@code .zshrc} 的文件不会是普通项目产物）。 */
    private static final Set<String> SHELL_CONFIG_FILES = Set.of(
            ".gitconfig", ".gitmodules", ".zshrc", ".bashrc", ".zshenv",
            ".zprofile", ".bash_profile", ".profile", ".npmrc",
            // direnv：cd 进目录就执行它，与 .vscode/tasks.json 同属「落盘即取得执行权」。
            // 在项目内更危险——ACCEPT_EDITS 会自动放行项目内的写。
            ".envrc");

    /** 「父目录名 / 文件名」形态的敏感配置（构建工具会读它们里的凭据与镜像地址）。 */
    private static final Set<String> SENSITIVE_NESTED = Set.of(
            ".m2/settings.xml", ".gradle/gradle.properties", ".kube/config",
            "gh/hosts.yml", "gh/config.yml",
            // XDG 形态：git 读 ~/.config/git/config 与 ~/.gitconfig 是同一份配置，
            // 同样能靠 core.pager / credential.helper / alias 劫持执行。
            // 只收了 ~/.gitconfig 而漏掉这个，等于给同一个动作留了第二种拼法。
            "git/config");

    /**
     * 写受保护、读也必须受保护的文件——它们既是「写进去会被劫持」也是「读出来就是凭据」。
     *
     * <p>{@code .npmrc} 此前只在 {@link #SHELL_CONFIG_FILES}（写方向）里，读方向完全放行，
     * 而它装的是 {@code //registry.npmjs.org/:_authToken=…}。
     * {@code .gitconfig} 同理，可携带 {@code url.…insteadOf} 里的内嵌 token。
     */
    private static final Set<String> READABLE_SECRET_CONFIGS =
            Set.of(".npmrc", ".gitconfig");

    /**
     * 写进去会「在别处被自动执行」的配置（父目录名 / 文件名形态）。
     *
     * <p>它们不是密钥，危险性在于<b>落盘即等于取得执行权</b>：编辑器打开项目就跑
     * {@code .vscode/tasks.json} 的任务，推一次分支就跑 workflow。
     */
    private static final Set<String> AUTO_EXEC_NESTED = Set.of(
            ".vscode/settings.json", ".vscode/tasks.json", ".vscode/launch.json",
            ".idea/workspace.xml", "fish/config.fish", "fish/conf.d");

    /**
     * 出现这一段就是「会被系统 / git 自动执行」的目录。
     *
     * <p>与 {@link #SHELL_CONFIG_FILES} 同一类风险，只是入口是<b>目录</b>而非固定文件名：
     * macOS 的 {@code LaunchAgents} / {@code LaunchDaemons} 放进一个 plist 就登录自启，
     * 而 {@code .githooks} 是 {@code core.hooksPath} 的惯用落点——与 {@code .git/hooks} 等效，
     * 却因为不在 {@code .git} 里而躲过了那条检查。
     */
    private static final Set<String> AUTO_EXEC_DIR_SEGMENTS =
            Set.of("launchagents", "launchdaemons", ".githooks");

    /** 一眼就是密钥/凭据的文件名（任意层级；密钥不因为躺在项目里就不是密钥）。 */
    private static final Set<String> SECRET_FILES = Set.of(
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            "credentials", "secring.gpg", ".netrc", ".pgpass", ".pypirc", ".git-credentials");

    /**
     * 系统级凭据文件的绝对路径——按<b>整条路径</b>比对，因为它们的文件名太普通
     * （{@code shadow} / {@code sudoers} 单看名字不像密钥，收进 {@link #SECRET_FILES} 会误伤同名普通文件）。
     */
    private static final Set<String> SYSTEM_SECRET_PATHS = Set.of(
            "/etc/shadow", "/etc/gshadow", "/etc/master.passwd", "/etc/sudoers", "/etc/krb5.keytab");

    /**
     * 私钥 / 证书的扩展名。
     *
     * <p>刻意<b>不</b>收 {@code .pub}——公钥就是给人看的，收了只会制造无谓的审批。
     * {@code .pem} 有可能是纯公钥证书链，仍然收：本层是 ASK 不是 DENY，宁可多问一次。
     */
    private static final Set<String> SECRET_EXTENSIONS = Set.of(
            ".key", ".pem", ".p12", ".pfx", ".jks", ".keystore", ".ppk", ".asc");

    /** {@code .codetui} 下属于 agent 自己工作区、不算危险的子目录。 */
    private static final Set<String> CODETUI_WORKSPACE = Set.of("memory", "sessions", "artifacts");

    /**
     * {@code .env} 的模板形态——按约定<b>不含</b>真实秘密，是提交进仓库给人抄的。
     *
     * <p>收在这里是为了不制造无谓审批：spec 把「审批疲劳导致用户一律选永久允许」列为
     * 本层要防的风险，那么本层自己就不该在日常文件上反复弹窗。
     */
    private static final Set<String> ENV_TEMPLATES =
            Set.of(".env.example", ".env.sample", ".env.template", ".env.dist");

    /** {@code /dev} 下写了也无妨的几个（重定向到它们是日常操作）。 */
    private static final Set<String> HARMLESS_DEVICES =
            Set.of("null", "stdout", "stderr", "stdin", "tty", "zero", "random", "urandom");

    /**
     * 写方向<b>可以</b>落地的区域——项目根、家目录、临时目录。
     *
     * <p><b>这一条是白名单而非黑名单，刻意如此</b>：{@code /etc/sudoers}（写进去就是提权）、
     * {@code /usr/local/bin/git}（劫持 PATH 上的每一次调用）、{@code /Library/…} 这类落点
     * 按文件名列永远列不全，而「agent 干活该往哪写」是可穷举的——项目内、家目录内、临时目录。
     * 其余位置都是系统级写入，问一次。
     *
     * <p>本检查放在所有具名检查<b>之后</b>，故家目录内的 {@code .ssh} / {@code .zshrc}
     * 仍由前面那些条命中（它们在白名单区内，但危险性另有来源）。
     */
    private static final Set<String> WRITABLE_ROOTS = Set.of(
            "/tmp", "/private/tmp", "/var/tmp", "/private/var/tmp", "/dev",
            // macOS 的真实每用户临时目录是 /var/folders/xx/…/T/，即 java.io.tmpdir。
            // 漏了它，每一次 mktemp / createTempFile 都要弹窗——而这是频率最高的写，
            // 恰是本类 javadoc 自述必须避免的那种审批疲劳（会把用户逼向「一律永久允许」）。
            "/var/folders", "/private/var/folders");

    /** 删除类命令。 */
    private static final Set<String> DELETE_COMMANDS = Set.of("rm", "rmdir", "shred", "srm");

    /**
     * 只是「把真正的命令包一层」的前缀词，剥掉后再看首词是什么。
     *
     * <p>{@code env} / {@code xargs} / {@code nice} 都是命令启动器，实测
     * {@code env rm -rf /} 曾整条漏过（首词是 {@code env}，不在删除类清单里）。
     */
    private static final Set<String> COMMAND_WRAPPERS = Set.of(
            "sudo", "doas", "time", "nohup", "command", "exec", "builtin", "eval",
            "env", "xargs", "nice", "ionice", "stdbuf", "setsid", "timeout");

    /**
     * shell 解释器——它们的 {@code -c} 载荷是一条完整命令，须递归检查。
     *
     * <p>刻意<b>不</b>收 {@code python} / {@code perl} / {@code ruby} / {@code node}：
     * 它们的载荷不是 shell 语法，本层解析不了。那一类由决策顺序兜——它们不在只读白名单里，
     * 故模式默认就是 ASK；且 {@code PermissionEngine.suggest} 明确不为解释器生成前缀规则，
     * 所以也不会被一条 {@code Bash(python:*)} 顺手放过。
     */
    private static final Set<String> SHELL_INTERPRETERS =
            Set.of("bash", "sh", "zsh", "dash", "ksh", "ash");

    /** 把文件内容写到参数所指位置、但不经 {@code >} 重定向的命令。 */
    private static final Set<String> WRITER_COMMANDS = Set.of("tee", "dd", "install");

    /** 会把整个目录原样搬走 / 打包带走的命令（拿 {@code ~/.ssh} 当参数就是在搬密钥）。 */
    private static final Set<String> BULK_COPY_COMMANDS = Set.of(
            "mv", "cp", "rsync", "tar", "zip", "scp", "base64", "openssl", "curl", "wget");

    private DangerousPaths() {
    }

    // ── 路径检查 ────────────────────────────────────────────────────────

    /**
     * 写检查；命中返回原因，否则 null。<b>逐个别名查一遍，任一命中即命中</b>。
     *
     * @param target 已由 {@code ToolTargets} 解析并规范化的目标路径，可为 null
     * @param root   项目根。<b>路径检查本身不需要它</b>（判据全是路径结构，见类注释），
     *               保留是为了与 {@link #checkRead} / {@link #checkCommand} 的签名一致，
     *               且 {@code checkCommand} 解析相对目标时确实要用
     */
    public static String checkWrite(Path target, Path root) {
        return firstHit(target, root, DangerousPaths::checkWriteOne);
    }

    /** 读检查；命中返回原因，否则 null。<b>逐个别名查一遍</b>。参数含义同 {@link #checkWrite}。 */
    public static String checkRead(Path target, Path root) {
        return firstHit(target, root, DangerousPaths::checkReadOne);
    }

    /**
     * 逐个写法查一遍，任一命中即命中。
     *
     * <p>底线没有 allow 方向（命中只会导致询问 / 拒绝），故这里放宽是<b>纯安全方向</b>
     * ——不需要引擎那套 deny/allow 不对称。别名由 {@link PathAliases} 给出
     * （原写法 + 符号链接解析后）；{@code target == null} 时别名列表为空，
     * 与两个 {@code *One} 自己的 null 守卫一样返回 null，行为不变。
     *
     * <p>代价是每次判定多 1–2 次文件系统调用（{@link PathAliases} 要爬到最长的已存在祖先）。
     * 命令检查里 {@code checkWrite} / {@code checkRead} 是<b>逐词</b>调的，故一条长命令会放大这个代价；
     * 本层是安全护栏、调用频率由用户操作决定，暂不优化。
     */
    private static String firstHit(Path target, Path root,
                                   java.util.function.BiFunction<Path, Path, String> check) {
        for (Path alias : PathAliases.of(target)) {
            String hit = check.apply(alias, root);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static String checkWriteOne(Path target, Path root) {
        if (target == null) {
            return null;
        }
        List<String> segs = segments(target);
        String name = segs.isEmpty() ? "" : segs.get(segs.size() - 1);

        // 密钥/凭据：读危险的，写同样危险（覆写密钥也是破坏）
        String secret = checkSecretFile(segs, name);
        if (secret != null) {
            return secret;
        }
        for (String dir : SENSITIVE_DIR_SEGMENTS) {
            if (segs.contains(dir)) {
                return "写入敏感目录 " + dir + "/（凭据与密钥所在）：" + target;
            }
        }
        if (segs.contains(".git")) {
            return "写入 .git/ 内部（可能改写仓库历史，或装上会在下次 git 操作时执行的 hook）：" + target;
        }
        String nested = nestedName(segs);
        if (nested != null && SENSITIVE_NESTED.contains(nested)) {
            return "写入构建工具的敏感配置 " + nested + "（含仓库凭据/镜像地址）：" + target;
        }
        if (nested != null && AUTO_EXEC_NESTED.contains(nested)) {
            return "写入 " + nested + "（这类配置会被编辑器 / CI 自动执行，等于取得执行权）：" + target;
        }
        if (segs.contains(".github") && segs.contains("workflows")) {
            return "写入 GitHub Actions workflow（推送后会在 CI 上执行）：" + target;
        }
        if (SHELL_CONFIG_FILES.contains(name)) {
            return "写入 shell / 工具配置 " + name + "（会影响之后在这台机器上执行的所有命令）：" + target;
        }
        for (String dir : AUTO_EXEC_DIR_SEGMENTS) {
            if (segs.contains(dir)) {
                return "写入 " + dir + "/（这个目录里的文件会被系统 / git 自动执行）：" + target;
            }
        }
        int codetui = segs.indexOf(".codetui");
        if (codetui >= 0 && !inAgentWorkspace(segs, codetui)) {
            return "写入 .codetui/ 配置（agent 正在修改自己的权限 / MCP 配置）：" + target;
        }
        // 裸设备：dd of=/dev/disk0 会直接覆写整块盘，绕过一切文件语义
        if (segs.size() == 2 && segs.get(0).equals("dev") && !HARMLESS_DEVICES.contains(segs.get(1))) {
            return "写入裸设备 " + target + "（会绕过文件系统直接覆写整块磁盘）";
        }
        // 兜底（白名单）：不在项目 / 家目录 / 临时目录内的写，都是系统级写入
        if (isOutsideWritableRoots(target, root)) {
            return "写入项目与家目录之外的系统位置（可能提权或劫持之后的命令）：" + target;
        }
        return null;
    }

    /**
     * 目标是否落在 {@link #WRITABLE_ROOTS} 与项目根 / 家目录之外。
     *
     * <p>相对路径按 {@code root} 解析（{@code ToolTargets} 交来的已是绝对路径，
     * 这里只是不把「没有 root」当成放行）。{@code root == null} 时只认家目录与白名单区。
     */
    private static boolean isOutsideWritableRoots(Path target, Path root) {
        Path abs = target.isAbsolute()
                ? target.normalize()
                : (root == null ? null : root.resolve(target).normalize());
        if (abs == null) {
            return false;            // 无从判定，交给别的检查，别在这里制造假阳性
        }
        if (root != null && abs.startsWith(root.normalize())) {
            return false;
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && abs.startsWith(Path.of(home).normalize())) {
            return false;
        }
        for (String w : WRITABLE_ROOTS) {
            if (abs.startsWith(Path.of(w))) {
                return false;
            }
        }
        return true;
    }

    private static String checkReadOne(Path target, Path root) {
        if (target == null) {
            return null;
        }
        List<String> segs = segments(target);
        String name = segs.isEmpty() ? "" : segs.get(segs.size() - 1);

        if (SYSTEM_SECRET_PATHS.contains(target.toString().toLowerCase(Locale.ROOT))) {
            return "读取系统凭据文件（内容即秘密本身）：" + target;
        }
        String secret = checkSecretFile(segs, name);
        if (secret != null) {
            return secret;
        }
        for (String dir : SECRET_STORE_DIRS) {
            if (segs.contains(dir)) {
                return "读取 " + dir + "/ 里的文件（整个目录都是凭据与密钥）：" + target;
            }
        }
        if (segs.contains(".ssh") && !SSH_PUBLIC_FILES.contains(name)) {
            return "读取 ~/.ssh 里的非公开文件（私钥不只叫 id_*）：" + target;
        }
        if (READABLE_SECRET_CONFIGS.contains(name)) {
            return "读取可能含 token 的工具配置 " + name + "：" + target;
        }
        // 主机私钥：文件名是 ssh_host_*_key，不带点、也不以 .key 结尾，故上面两条都不命中
        if (name.startsWith("ssh_host_") && name.endsWith("_key")) {
            return "读取 SSH 主机私钥（内容即秘密本身）：" + target;
        }
        String nested = nestedName(segs);
        if (nested != null && SENSITIVE_NESTED.contains(nested)) {
            return "读取含凭据的配置 " + nested + "：" + target;
        }
        return null;
    }

    /** 密钥/凭据文件名（读写通用）。 */
    private static String checkSecretFile(List<String> segs, String name) {
        if (SECRET_FILES.contains(name)) {
            return "访问密钥 / 凭据文件 " + name + "（内容即秘密本身）";
        }
        if ((name.equals(".env") || name.startsWith(".env.")) && !ENV_TEMPLATES.contains(name)) {
            return "访问 " + name + "（按约定存放 API key 等秘密）";
        }
        if (segs.contains(".ssh") && name.startsWith("id_")) {
            return "访问 SSH 私钥 " + name;
        }
        // 私钥的扩展名形态：server.key / cert.pem / keystore.p12
        for (String ext : SECRET_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return "访问密钥 / 证书文件 " + name + "（" + ext + " 通常是私钥或凭据）";
            }
        }
        return null;
    }

    // ── 命令检查 ────────────────────────────────────────────────────────

    /**
     * 命令检查；命中返回原因，否则 null。
     *
     * <p><b>为什么必须逐段扫、且不能只看首词</b>（计划原文只判 {@code command.startsWith("rm ")}，
     * 已实测下列拼法全部漏过）：{@code ls && rm -rf /}、{@code sudo rm -rf /}、
     * {@code /bin/rm -rf /}、{@code rm -rf "/"}、{@code rm -rf /*}、{@code rm -rf //}。
     * 而本层跑在 allow 规则与模式默认<b>之前</b>，一旦漏过，BYPASS 模式或一条
     * {@code allow:Bash(*)} 就能把它直接执行掉——那正是本层存在的理由失效。
     *
     * <p>{@code root} 用来解析相对目标：{@code rm -rf ../..} 在 {@code /work/proj} 下就是 {@code /}，
     * 不给基准就无从判断（这也是本方法比计划多一个参数的原因）。
     */
    public static String checkCommand(String command, Path root) {
        if (command == null || command.isBlank()) {
            return null;
        }
        for (String seg : segmentsToCheck(command)) {
            String hit = checkSegment(seg, root);
            if (hit != null) {
                return hit;
            }
        }
        if (!BashCommandSplitter.split(command).parseable()) {
            return checkHeadIndependently(command, root);
        }
        return null;
    }

    /**
     * 拆不动时的兜底：<b>完全不依赖 head 位置</b>逐词扫一遍。
     *
     * <p><b>为什么需要它</b>：宽松拆段不认引号，会从命令和它的目标<b>之间</b>切开，
     * 于是每一个依赖 head 的检查（删除 / 写入 / 拷贝）都失效——实测
     * {@code echo "a;b" ; rm -rf "p;q" /} 拆成 {@code [echo "a}、{@code b" }、
     * {@code  rm -rf "p}、{@code q" /}]：带 {@code rm} 的那段没有 {@code /}，
     * 带 {@code /} 的那段 head 是 {@code q"}。整串扫描也救不回来，整串的 head 是 {@code echo}。
     *
     * <p>结果是加一对引号就能把「拦住」变成「漏过」——回退比不回退还危险。
     * 故这里改判据：<b>只要整串里出现了危险命令名，就把每一个词都当作可能的落点检查</b>。
     * 过宽（{@code echo "rm" $(x)} 也会问），但本层只产出 ASK，方向正确；
     * 而且只在拆不动时才走这条路，日常命令不受影响。
     */
    private static String checkHeadIndependently(String command, Path root) {
        List<String> words = words(command);
        boolean destructive = false;
        String deleteHead = null;
        for (String w : words) {
            String base = w.substring(w.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
            if (DELETE_COMMANDS.contains(base)) {
                destructive = true;
                deleteHead = base;
                break;
            }
            if (WRITER_COMMANDS.contains(base) || BULK_COPY_COMMANDS.contains(base)) {
                destructive = true;
                break;
            }
        }
        if (!destructive) {
            return null;
        }
        // 「删掉 home / 根本身」靠的是结构位置判定，那条同样依赖 head，也得在这里重跑一遍：
        // checkWrite(~) 是 null（家目录本就可写），但 rm -rf ~ 显然要问。
        if (deleteHead != null && hasRecursiveFlag(words)) {
            String hit = checkDeleteTargets(deleteHead, words, root);
            if (hit != null) {
                return "命令结构无法可靠解析，且其中出现了递归删除——" + hit;
            }
        }
        for (String w : words) {
            if (isOption(w)) {
                continue;
            }
            String hit = checkWrite(resolveToken(stripTrailingGlob(w), root), root);
            if (hit != null) {
                return "命令结构无法可靠解析，且其中出现了会落盘 / 删除的命令——" + hit;
            }
        }
        return null;
    }

    private static String checkSegment(String segment, Path root) {
        List<String> words = words(segment);
        if (words.isEmpty()) {
            return null;
        }
        String head = headCommand(words);

        // shell 的 -c 载荷本身就是一条命令，递归查它。
        // 不能靠「把 bash 加进 COMMAND_WRAPPERS」了事：那只会让 head 跳到 -c 之后，
        // 而载荷是带引号的整串，会被按空白切碎，rm 和它的目标就此分家。
        // 本仓库自己早就知道这件事——BashCommandSplitter 的 javadoc 正是拿
        // bash -c 'echo hi\nrm -rf /' 当作「换行必须算分隔符」的立论依据。
        if (SHELL_INTERPRETERS.contains(head)) {
            String payload = dashCPayload(words);
            if (payload != null) {
                String hit = checkCommand(payload, root);
                if (hit != null) {
                    return head + " -c 的载荷本身是一条命令——" + hit;
                }
            }
        }

        if (DELETE_COMMANDS.contains(head)) {
            if (hasRecursiveFlag(words)) {
                String hit = checkDeleteTargets(head, words, root);
                if (hit != null) {
                    return hit;
                }
            }
            // 删除目标也要过 checkWrite：本层保护一个文件不被写，就没有理由不保护它不被删。
            // 此前删除只走上面那条「结构位置」判定（根 / 顶层 / home），且以 -r 为门，于是
            // rm -f /etc/sudoers、rm ~/.zshrc、rm -f ~/Library/LaunchAgents/x.plist 全部漏过——
            // 而同名目标的 checkWrite 是有理由返回的。非递归删除同样致命，故不设 -r 门。
            for (String w : words.subList(1, words.size())) {
                if (isOption(w)) {
                    continue;
                }
                String hit = checkWrite(resolveToken(stripTrailingGlob(w), root), root);
                if (hit != null) {
                    return head + " 会删除敏感落点——" + hit;
                }
            }
        }
        // 重定向落点：echo evil >> ~/.zshrc 的首词是 echo，但它落盘
        for (String t : redirectionTargets(segment)) {
            String hit = checkWrite(resolveToken(t, root), root);
            if (hit != null) {
                return "命令会写入敏感落点——" + hit;
            }
        }
        // tee / dd 一类：不经 > 就把内容写到参数所指的位置
        if (WRITER_COMMANDS.contains(head)) {
            for (String w : words.subList(1, words.size())) {
                if (isOption(w)) {
                    continue;
                }
                String hit = checkWrite(resolveToken(stripDdPrefix(w), root), root);
                if (hit != null) {
                    return head + " 会写入敏感落点——" + hit;
                }
            }
        }
        // 整目录搬走 / 打包带走：tar cf - ~/.ssh、mv ~/.ssh /tmp/x
        if (BULK_COPY_COMMANDS.contains(head)) {
            for (String w : words.subList(1, words.size())) {
                if (isOption(w)) {
                    continue;
                }
                Path t = resolveToken(stripAtPrefix(w), root);
                if (t != null && touchesSensitiveDir(t)) {
                    return head + " 会整体搬走 / 打包密钥目录 " + t + "（等同于把密钥带出这台机器）";
                }
            }
            // 落点也要过 checkWrite：此前只判「参数是不是密钥目录」，于是
            // cp evil /usr/local/bin/git、curl -o /etc/sudoers、mv evil ~/.bashrc 全部漏过——
            // 而 install / tee 同样的动作被拦住，差别只在它们恰好在 WRITER_COMMANDS 表里。
            // 凡是指名落点的命令，落点都应过 checkWrite，而不是由「命令名在哪张表」决定检查跑不跑。
            String dest = copyDestination(words);
            if (dest != null) {
                String hit = checkWrite(resolveToken(dest, root), root);
                if (hit != null) {
                    return head + " 会写入敏感落点——" + hit;
                }
            }
        }
        // 递归扫描一整棵以家目录 / 根为起点的树——与命令名无关的结构性判据。
        // grep -R AKIA ~ 的目标是家目录本身，不是任何具名密钥路径，所以上面每一条都不命中；
        // 而 grep 在只读白名单里，于是 DEFAULT 模式下无需任何规则就自动放行——
        // 一次静默的全家目录凭据扫描。判据不是「grep 危险」，是「扫描范围等于整个家目录」。
        if (hasRecursiveFlag(words)) {
            for (String w : words.subList(1, words.size())) {
                if (isOption(w)) {
                    continue;
                }
                Path t = resolveToken(stripTrailingGlob(w), root);
                if (t != null && isScanEverything(t)) {
                    return head + " 会递归扫描 " + t + " 整棵树（家目录 / 根级别的全量扫描，"
                            + "足以一次性读出所有凭据）";
                }
            }
        }
        // 命令参数里出现密钥路径：cat ~/.ssh/id_rsa 一类
        for (String w : words) {
            if (isOption(w)) {
                continue;
            }
            String hit = checkRead(resolveToken(stripAtPrefix(w), root), root);
            if (hit != null) {
                return "命令会读取密钥 / 凭据——" + hit;
            }
        }
        return null;
    }

    /**
     * 取「拷贝类」命令的<b>落点</b>参数（不是全部参数）。
     *
     * <p>只判落点是刻意的：全判会把 {@code cp ~/.zshrc /tmp/backup} 的<b>源</b>误报成「写入敏感落点」，
     * 而它其实是读——读的风险由下面那条通用读循环负责，理由措辞也才对得上。
     *
     * <ul>
     *   <li>{@code -o} / {@code --output} / {@code -O} 后面那个词（{@code curl -o F}、{@code wget -O F}）；
     *       等号形态 {@code --output=F} 一并认。</li>
     *   <li>{@code -C} 后面那个词（{@code tar -xf p.tar -C /} 的解包落点）。</li>
     *   <li>否则取<b>最后一个</b>非选项参数（{@code cp}/{@code mv}/{@code scp}/{@code rsync} 的语义）。</li>
     * </ul>
     *
     * <p>取不到就返回 {@code null}（只有一个参数时没有落点可言，如 {@code tar cf - ~/.ssh}）。
     */
    private static String copyDestination(List<String> words) {
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            if (w.startsWith("--output=")) {
                return w.substring("--output=".length());
            }
            boolean flagWithValue = "-o".equals(w) || "-O".equals(w)
                    || "--output".equals(w) || "-C".equals(w);
            if (flagWithValue && i + 1 < words.size()) {
                return words.get(i + 1);
            }
        }
        String last = null;
        int count = 0;
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            if (isOption(w)) {
                continue;
            }
            last = w;
            count++;
        }
        return count >= 2 ? last : null;    // 只有一个非选项参数时它是源，不是落点
    }

    /**
     * 取 shell 的 {@code -c} 载荷；没有则返回 {@code null}。
     *
     * <p>{@code -c} 之后的<b>全部</b>词拼回去再脱引号——载荷通常是带空格的整串
     * （{@code bash -c "rm -rf /"} 经 {@code words()} 会切成 {@code "rm}、{@code -rf}、{@code /"}）。
     * 也认 {@code -lc} / {@code -ic} 这类组合短选项。
     */
    private static String dashCPayload(List<String> words) {
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            boolean isDashC = "-c".equals(w)
                    || (w.startsWith("-") && !w.startsWith("--") && w.endsWith("c"));
            if (isDashC && i + 1 < words.size()) {
                String joined = String.join(" ", words.subList(i + 1, words.size())).trim();
                return stripQuotes(joined);
            }
        }
        return null;
    }

    /**
     * 目标是不是「家目录 / 根」这一级——递归扫描它等于把机器上的凭据一次读个遍。
     *
     * <p>刻意<b>不</b>把项目根算进来：{@code grep -R TODO <root>} 是日常操作，
     * 收进来只会制造审批疲劳，而项目内的秘密另有 {@code .env} 一类具名检查兜。
     */
    private static boolean isScanEverything(Path t) {
        if (t.getNameCount() == 0) {
            return true;                                    // 根本身
        }
        Path home = Path.of(System.getProperty("user.home")).normalize();
        if (t.equals(home)) {
            return true;
        }
        List<String> segs = segments(t);
        // /Users/<someone> 与 /home/<someone>：别人的家目录同样是全量扫描
        return segs.size() == 2 && ("users".equals(segs.get(0)) || "home".equals(segs.get(0)));
    }

    /** 路径是不是就是某个敏感目录本身（{@code ~/.ssh}），而不是它里面的某个文件。 */
    private static boolean touchesSensitiveDir(Path t) {
        List<String> segs = segments(t);
        if (segs.isEmpty()) {
            return false;
        }
        return SENSITIVE_DIR_SEGMENTS.contains(segs.get(segs.size() - 1));
    }

    /** {@code dd of=/dev/disk0} 的 {@code of=} 前缀。 */
    private static String stripDdPrefix(String w) {
        int eq = w.indexOf('=');
        return eq >= 0 ? w.substring(eq + 1) : w;
    }

    /** {@code curl -d @file} 的 {@code @} 前缀。 */
    private static String stripAtPrefix(String w) {
        return w.startsWith("@") ? w.substring(1) : w;
    }

    /**
     * 删除类命令的目标是否触到底线。
     *
     * <p>判据是<b>结构性</b>的，不是「危险目录清单」——清单永远漏掉没人想到的那一项：
     * 顶层目录（{@code /}、{@code /usr}、{@code /etc}、任何 {@code /x}）、家目录及其祖先、
     * 项目根及其祖先、别人的家目录（{@code /Users/x}、{@code /home/x}）。
     * 项目内的相对目录（{@code rm -rf target}）刻意不算——那是日常操作，由模式默认负责问。
     */
    private static String checkDeleteTargets(String head, List<String> words, Path root) {
        Path home = home();
        for (String w : words.subList(1, words.size())) {
            if (isOption(w)) {
                continue;
            }
            String cleaned = stripQuotes(w);
            if (cleaned.isEmpty()) {
                continue;
            }
            String expanded = expandHome(cleaned, home);
            if (expanded.indexOf('$') >= 0) {
                return head + " 递归删除变量目标 " + w + "：展开结果无法核实，不做猜测";
            }
            Path t = resolveToken(cleaned, root);
            if (t == null || !t.isAbsolute()) {
                continue;                       // 解析不出绝对路径 → 交模式默认去问
            }
            if (t.getNameCount() == 0) {
                return head + " 递归删除文件系统根 " + w + "：会清空整个磁盘";
            }
            if (t.getNameCount() == 1) {
                return head + " 递归删除顶层目录 " + t + "：属于系统或整块数据，不是项目内的目录";
            }
            if (home != null && (t.equals(home) || home.startsWith(t))) {
                return head + " 递归删除家目录 " + t + "：会清空这个账号的全部个人文件";
            }
            if (isHomeLike(t)) {
                return head + " 递归删除某个账号的家目录 " + t + "：会清空该账号的全部个人文件";
            }
            if (root != null) {
                Path r = root.normalize();
                if (t.equals(r) || r.startsWith(t)) {
                    return head + " 递归删除 " + t + "：这是整个工作区（或它的上层目录）";
                }
            }
        }
        return null;
    }

    /** 是否带递归开关（{@code -r} / {@code -R} / 捆绑短选项 / {@code --recursive}）。 */
    private static boolean hasRecursiveFlag(List<String> words) {
        for (String w : words) {
            if (!isOption(w)) {
                continue;
            }
            if (w.startsWith("--")) {
                if (w.equals("--recursive") || w.equals("--dir")) {
                    return true;
                }
                continue;
            }
            if (w.indexOf('r') >= 0 || w.indexOf('R') >= 0) {
                return true;                    // -rf / -fr / -rvf / -R 都在此
            }
        }
        return false;
    }

    /** 剥掉 {@code sudo} / {@code time} 一类包装词与路径前缀，取出真正的命令名。 */
    private static String headCommand(List<String> words) {
        for (String w : words) {
            String c = stripQuotes(w);
            if (c.startsWith("\\")) {
                c = c.substring(1);             // \rm 绕过 alias
            }
            if (c.isEmpty() || isOption(c) || c.indexOf('=') >= 0) {
                continue;                       // 选项与 VAR=value 都不是命令名
            }
            // 小写化：APFS 大小写不敏感，/bin/LS 真能执行（实测 `bash -c 'LS -d /'` 成功），
            // 故 RM -rf / 与 rm -rf / 是同一条命令。四张命令表都是小写，这里不折叠就整层可绕。
            String base = c.substring(c.lastIndexOf('/') + 1)
                    .toLowerCase(Locale.ROOT);                   // /bin/RM → rm
            if (COMMAND_WRAPPERS.contains(base)) {
                continue;
            }
            if (isNumeric(base)) {
                continue;                       // timeout 5 rm …：5 是包装词的参数，不是命令名
            }
            return base;
        }
        return "";
    }

    /** 段内引号外的重定向落点（{@code > f}、{@code >>f}、{@code 2> f}、{@code &> f}）。 */
    private static List<String> redirectionTargets(String segment) {
        List<String> out = new ArrayList<>();
        char quote = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c != '>') {
                continue;
            }
            int j = i + 1;
            while (j < segment.length()
                    && (segment.charAt(j) == '>' || segment.charAt(j) == '&'
                        || segment.charAt(j) == '|'          // >| 是 bash 的强制覆写形态
                        || Character.isWhitespace(segment.charAt(j)))) {
                j++;
            }
            int start = j;
            while (j < segment.length() && !Character.isWhitespace(segment.charAt(j))) {
                j++;
            }
            if (j > start) {
                out.add(segment.substring(start, j));
            }
            i = j - 1;
        }
        return out;
    }

    /**
     * 把命令里的一个 token 解析成可判定的绝对路径。
     *
     * <p>依次处理：去引号 → 展开 {@code ~} / {@code $HOME} → 去掉尾部通配（{@code /*} 等）
     * → 相对路径按 root 解析 → {@code normalize()}。
     * {@code normalize()} 顺带把 {@code //}、{@code /.}、{@code /..} 都收敛成 {@code /}（已实测）。
     */
    private static Path resolveToken(String token, Path root) {
        try {
            String s = stripQuotes(token);
            if (s.isEmpty()) {
                return null;
            }
            s = expandHome(s, home());
            s = stripTrailingGlob(s);
            if (s.isEmpty() || s.indexOf('$') >= 0) {
                return null;
            }
            Path p = Path.of(s);
            if (!p.isAbsolute() && root != null) {
                p = root.resolve(p);
            }
            return p.normalize();
        } catch (RuntimeException e) {
            return null;                        // 非法路径字符 → 无法核实，交模式默认
        }
    }

    /** {@code ~} / {@code ~/x} / {@code $HOME} / {@code ${HOME}} 展开成真实家目录。 */
    private static String expandHome(String s, Path home) {
        if (home == null) {
            return s;
        }
        String h = home.toString();
        if (s.equals("~")) {
            return h;
        }
        if (s.startsWith("~/")) {
            return h + s.substring(1);
        }
        if (s.startsWith("$HOME")) {
            return h + s.substring("$HOME".length());
        }
        if (s.startsWith("${HOME}")) {
            return h + s.substring("${HOME}".length());
        }
        return s;
    }

    /** 去掉尾部的通配片段：{@code /Users/*} → {@code /Users}，{@code /*} → {@code /}。 */
    private static String stripTrailingGlob(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '*' || s.charAt(end - 1) == '?')) {
            end--;
        }
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return end == s.length() ? s : s.substring(0, end == 0 ? 1 : end);
    }

    /** 去掉 token 里的全部引号（{@code "$B"/x} 这种半包形态也要拆开看）。 */
    private static String stripQuotes(String s) {
        if (s.indexOf('\'') < 0 && s.indexOf('"') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\'' && c != '"') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 拆出所有<b>需要逐段检查</b>的片段。
     *
     * <p>先走 {@link BashCommandSplitter#split}——那是 Task 3 的组件、经过 2231 例对抗性 fuzz，
     * 「什么算一条命令」以它为准，本类不另立定义（两个组件对此给出不同答案，
     * 在本项目已经制造过两个 Critical）。
     *
     * <p><b>但它 {@code parseable=false} 时 {@code segments()} 是空的</b>（实测：
     * {@code foo; rm -rf /; echo $(x)} 拆出 0 段，里面那条真的 {@code rm -rf /} 就没人看了）。
     * 对拆分器而言这是正确的失败关闭——它的调用方会因此 ASK；
     * <b>但对本层不是</b>：本层跑在 allow 规则与模式默认之前，返回 null 等于放行。
     * 故拆不动时改用宽松拆分（只按 {@link BashCommandSplitter#isSeparatorChar} 断句、绝不放弃），
     * 并把整条原串也扫一遍——<b>宁可多问，绝不因为看不懂就放过</b>。
     *
     * <p>分隔符清单仍然只有一份，在 {@code BashCommandSplitter} 那边。
     */
    private static List<String> segmentsToCheck(String command) {
        List<String> out = new ArrayList<>();
        BashCommandSplitter.Split split = BashCommandSplitter.split(command);
        if (split.parseable()) {
            out.addAll(split.segments());
        } else {
            out.addAll(lenientSplit(command));
        }
        out.add(command.trim());        // 整条也扫：分隔符藏在引号里时，拆出的段可能把目标切碎
        return out;
    }

    /**
     * 宽松拆段——只在 {@link BashCommandSplitter#split} 拆不动时兜底。
     * 与它的区别只有一条：<b>本方法绝不放弃</b>，因为在本层放弃就等于放行。
     */
    private static List<String> lenientSplit(String command) {
        List<String> segs = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (BashCommandSplitter.isSeparatorChar(c)) {
                addSegment(segs, cur);
            } else {
                cur.append(c);
            }
        }
        addSegment(segs, cur);
        return segs;
    }

    private static void addSegment(List<String> segs, StringBuilder cur) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) {
            segs.add(s);
        }
        cur.setLength(0);
    }

    private static boolean isOption(String w) {
        return w.length() > 1 && w.charAt(0) == '-';
    }

    /** 纯数字（{@code timeout 5 rm …} 里的 5 是包装词的参数，不是命令名）。 */
    private static boolean isNumeric(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> words(String segment) {
        String s = segment == null ? "" : segment.trim();
        return s.isEmpty() ? List.of() : List.of(s.split("\\s+"));
    }

    /** 目标是不是「某个账号的家目录」形态（{@code /Users/x}、{@code /home/x}）。 */
    private static boolean isHomeLike(Path t) {
        if (t.getNameCount() != 2) {
            return false;
        }
        String top = t.getName(0).toString().toLowerCase(Locale.ROOT);
        return top.equals("users") || top.equals("home");
    }

    private static Path home() {
        try {
            String h = System.getProperty("user.home");
            return h == null || h.isBlank() ? null : Path.of(h).normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 路径各段，<b>一律转小写</b>（大小写不敏感盘上 {@code .ENV} 与 {@code .env} 是同一文件）。 */
    private static List<String> segments(Path p) {
        Path n = p.normalize();
        List<String> out = new ArrayList<>(n.getNameCount());
        for (int i = 0; i < n.getNameCount(); i++) {
            out.add(n.getName(i).toString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 末两段拼成 {@code 父目录/文件名}，供 {@link #SENSITIVE_NESTED} 比对。 */
    private static String nestedName(List<String> segs) {
        int n = segs.size();
        return n < 2 ? null : segs.get(n - 2) + "/" + segs.get(n - 1);
    }

    /** {@code .codetui} 之后紧跟的那一段是否是 agent 自己的工作区。 */
    private static boolean inAgentWorkspace(List<String> segs, int codetuiIndex) {
        int next = codetuiIndex + 1;
        return next < segs.size() && CODETUI_WORKSPACE.contains(segs.get(next));
    }
}

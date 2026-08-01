package io.github.javaside.springai.codetui.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 读取两层 {@code permissions.json}（用户级 {@code ~/.codetui/permissions.json} +
 * 项目级 {@code <root>/.codetui/permissions.json}），合并为一份 {@link PermissionConfig}。
 *
 * <p><b>合并而非覆盖</b>：两层的 allow/ask/deny 全部保留并集——deny 只增不减，
 * 项目层<b>不能</b>削弱用户层的禁令。
 *
 * <p><b>降级契约</b>（照 {@code McpConfigLoader}）：文件缺失 / JSON 非法 / 单条规则非法 /
 * {@code defaultMode} 未知 → 记 WARN、跳过、<b>绝不抛异常</b>。
 * 权限层挂了会把整个 agent 一起带走，比没有权限层更糟。
 *
 * <h2>贯穿原则：项目层只能收紧，不能放宽</h2>
 * <p>{@code ~/.codetui/} 是<b>用户自己</b>的配置，{@code <root>/.codetui/} 是<b>仓库带来</b>的配置。
 * <b>clone 一个仓库不得让 agent 变得更宽松。</b>本类据此关掉三条放宽路径：
 *
 * <ol>
 *   <li><b>{@code defaultMode} 不接受 {@link PermissionMode#BYPASS}</b>（两层皆然）——
 *       见下节；</li>
 *   <li><b>项目层的 allow 不得是通配放行</b>——见 {@link #isBlanketAllow}。
 *       决策顺序里一条 ALLOW 命中在第 5 步短路，与 BYPASS 在第 6 步做的事完全一致，
 *       所以 {@code {"allow":["*"]}} 就是一个换了写法的 {@code --dangerously-skip-permissions}；</li>
 *   <li><b>项目层的 {@code defaultMode} 只能比用户层严</b>——见 {@link #stricter}。
 *       用户显式选了 {@code DEFAULT}，仓库不能替他改成 {@code ACCEPT_EDITS} 去自动批准写操作。</li>
 * </ol>
 *
 * <p>三条限制都<b>只针对放宽方向</b>：项目层的 deny / ask（含通配形态）、收窄型的项目层 allow
 * （{@code Bash(mvn test:*)}——「允许，永久」写下的正是这个形态）、以及往严处走的 {@code defaultMode}
 * 一律照常生效。用户层不受通配限制：那是用户自己的机器。
 *
 * <h2>为什么配置文件不能声明 BYPASS</h2>
 * <p>{@link PermissionMode#BYPASS} 的契约是「仅 {@code --dangerously-skip-permissions} 启动可进」，
 * 而本文件是 <b>agent 自己写得到</b>的——它有 Write 工具，项目层文件又跟着仓库走。
 * 若认这个取值，「全放行」就有了两条不经人手的入口：一次被劫持的回合写一行 JSON，
 * 或者 clone 一个仓库。故 {@code defaultMode:"BYPASS"} 一律记 WARN 后当非法值丢弃。
 *
 * <p>顺带一提，这不是本层唯一的防线：{@code DangerousPaths.checkWrite} 会把写
 * {@code <root>/.codetui/} 判成危险动作。但那条防线只覆盖项目层、且只在路径能被解析出来时生效
 * （{@code Bash} 里的 {@code echo >> permissions.json} 就绕过去了），
 * 所以取值本身也得在这里关死。
 *
 * <h2>为什么开重复键检测</h2>
 * <p>Jackson 默认<b>末键胜出</b>：{@code {"deny":[…],"allow":[…],"deny":[]}} 里第一条 deny 被静默丢弃。
 * 人读文件看到一条 deny 在生效，运行时它并不存在——<b>而这个文件是 agent 可写的</b>。
 * 故启用 {@link StreamReadFeature#STRICT_DUPLICATE_DETECTION}，重复键按整文件非法处理（安全方向）。
 */
public final class PermissionConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigLoader.class);

    /**
     * 开了重复键检测的 mapper（见类注释「为什么开重复键检测」）。
     * 检测在流式解析层，故必须经 {@link JsonMapper#builder()} 配置，{@code new ObjectMapper()} 上没有开关。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** 认识的顶层字段；其余一律记 WARN（拼错大小写的 {@code Deny} 是一条隐形缺失的禁令）。 */
    private static final Set<String> KNOWN_KEYS = Set.of("defaultMode", "allow", "ask", "deny");

    /**
     * 「这条路径 glob 够不着的地方」探针：项目层 allow 只要命中其中<b>任意一条</b>，
     * 就说明它的覆盖面已经溢出仓库、伸进系统配置或用户密钥，按通配放行拒绝。
     *
     * <p>选这三条是因为 {@code DangerousPaths} 本身就把它们当敏感目标
     * （{@code .ssh} / {@code .aws} 在其敏感目录清单里）——判据因此与内置危险检查同源，
     * 而不是另立一套标准。
     *
     * <p><b>已知边界</b>：这是「是否覆盖全盘」的探针，<b>不是</b>「是否越出工作区」的判定。
     * {@code Write(/var/**)} 这类指向工作区外某个具体目录的项目层 allow 不会被它拦下
     * （两参 {@code load} 拿不到项目根，无从判断「工作区外」）。
     * 拦不下的那些仍受决策顺序第 2 步的内置危险检查约束。
     */
    private static final List<String> COVERAGE_CANARIES = List.of(
            "/etc/passwd",
            System.getProperty("user.home") + "/.ssh/id_rsa",
            System.getProperty("user.home") + "/.aws/credentials");

    private PermissionConfigLoader() {
    }

    /** 生产入口：由项目根解析出两层文件路径后加载。 */
    public static PermissionConfig load(Path root) {
        // root 为 null 时只加载用户层：两参重载能容忍 null，这里也不该 NPE
        return load(userFile(), root == null ? null : projectFile(root));
    }

    /** 用户层文件路径（回写「永久允许」时也用它判目标层）。 */
    public static Path userFile() {
        return Path.of(System.getProperty("user.home")).resolve(".codetui").resolve("permissions.json");
    }

    /** 项目层文件路径（「允许，永久」默认回写到这里）。 */
    public static Path projectFile(Path root) {
        return root.resolve(".codetui").resolve("permissions.json");
    }

    /** 可测入口：显式两文件。 */
    public static PermissionConfig load(Path userFile, Path projectFile) {
        List<PermissionRule> rules = new ArrayList<>();
        JsonNode userRoot = readTree(userFile);
        JsonNode projectRoot = readTree(projectFile);

        collect(userRoot, RuleScope.USER, rules);
        collect(projectRoot, RuleScope.PROJECT, rules);

        return new PermissionConfig(resolveMode(userRoot, projectRoot), List.copyOf(rules));
    }

    /**
     * 定 {@code defaultMode}：用户层缺省即 {@link PermissionMode#DEFAULT}；
     * 项目层的取值<b>只在不比用户层宽时</b>采纳，否则记 WARN 并保留用户层的值。
     *
     * <p>由此，项目层实际只剩「把用户选的 {@code ACCEPT_EDITS} 压回 {@code DEFAULT}」一种作用
     * ——这是刻意的：{@code DEFAULT} 已是最严，往严处已无处可去。
     */
    private static PermissionMode resolveMode(JsonNode userRoot, JsonNode projectRoot) {
        PermissionMode user = parseMode(userRoot, RuleScope.USER);
        PermissionMode effective = user == null ? PermissionMode.DEFAULT : user;

        PermissionMode project = parseMode(projectRoot, RuleScope.PROJECT);
        if (project == null) {
            return effective;
        }
        if (!stricter(project, effective)) {
            log.warn("已忽略项目级 defaultMode=\"{}\"——它比当前生效的 \"{}\" 更宽松，"
                            + "仓库带来的配置只能收紧、不能替你放宽。需要更宽松请改用户级 {} 或用 Shift+Tab 临时切换。",
                    project, effective, userFile());
            return effective;
        }
        return project;
    }

    /** {@code a} 是否不比 {@code b} 宽松。严格程度：PLAN ≥ DEFAULT ≥ ACCEPT_EDITS ≥ BYPASS（BYPASS 两层皆已被拒）。 */
    private static boolean stricter(PermissionMode a, PermissionMode b) {
        return strictness(a) >= strictness(b);
    }

    private static int strictness(PermissionMode m) {
        return switch (m) {
            case PLAN -> 3;   // 只读之外一律 DENY，是最严的一档
            case DEFAULT -> 2;
            case ACCEPT_EDITS -> 1;
            case BYPASS -> 0;
        };
    }

    private static JsonNode readTree(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            // 重复键也走这里：Jackson 抛 StreamReadException，被转成「整文件跳过」——安全方向
            log.warn("权限配置解析失败，忽略整个文件：{}（{}）", file, e.getMessage());
            return null;
        }
    }

    private static void collect(JsonNode root, RuleScope scope, List<PermissionRule> out) {
        if (root == null) {
            return;
        }
        warnUnknownKeys(root);
        add(root.get("deny"), PermissionBehavior.DENY, scope, out);
        add(root.get("ask"), PermissionBehavior.ASK, scope, out);
        add(root.get("allow"), PermissionBehavior.ALLOW, scope, out);
    }

    /**
     * 未识别的顶层字段记 WARN。
     *
     * <p>其余每条跳过路径都有 WARN，唯独这里此前是静默的——而一个拼错大小写的 {@code "Deny"}
     * 就是一条<b>人以为在生效、运行时并不存在</b>的禁令，和重复键是同一类事故。
     */
    private static void warnUnknownKeys(JsonNode root) {
        if (!root.isObject()) {
            log.warn("权限配置顶层不是 JSON 对象，未取到任何规则。");
            return;
        }
        for (String key : root.propertyNames()) {
            if (!KNOWN_KEYS.contains(key)) {
                log.warn("权限配置里有未识别的字段 \"{}\"，已忽略。可用字段：defaultMode / allow / ask / deny"
                        + "（区分大小写，拼错的 deny 不会生效）。", key);
            }
        }
    }

    private static void add(JsonNode array, PermissionBehavior behavior,
                            RuleScope scope, List<PermissionRule> out) {
        if (array == null) {
            return;
        }
        if (!array.isArray()) {
            log.warn("权限配置 {} 字段不是数组，忽略该字段。", behavior.name().toLowerCase());
            return;
        }
        for (JsonNode n : array) {
            // Jackson 3 的 stringValue() 对非文本节点会抛，必须先判 isString
            if (!n.isString()) {
                log.warn("权限规则不是字符串，跳过：{}", n);
                continue;
            }
            String dsl = n.stringValue();
            PermissionRule r = PermissionRule.parse(dsl, behavior, scope);
            if (r == null) {
                // parse 返回 null 即语法非法（括号不闭合 / 空工具名 / 空括号），绝不能让 null 进列表
                log.warn("权限规则语法非法，跳过：{}（形如 \"Read(*)\" / \"Bash(git push:*)\"）", dsl);
                continue;
            }
            if (scope == RuleScope.PROJECT && behavior == PermissionBehavior.ALLOW && isBlanketAllow(r)) {
                log.warn("已忽略项目级配置里的通配放行 \"{}\"——仓库带来的配置不得整工具 / 全工具 / 全盘路径放行"
                                + "（那等价于 --dangerously-skip-permissions）。"
                                + "请改写成收窄形态（如 \"Bash(mvn test:*)\" / \"Write(src/**)\"），"
                                + "或放进用户级 {}。",
                        dsl, userFile());
                continue;
            }
            warnUnknownTool(r, dsl);
            out.add(r);
        }
    }

    /**
     * 这条项目层 allow 是不是「通配放行」——即它的覆盖面与 {@link PermissionMode#BYPASS} 无异。
     *
     * <p>三种形态：
     * <ol>
     *   <li>{@code toolName == "*"}：命中<b>任意工具</b>；</li>
     *   <li>{@code pattern == null}：该工具<b>全部调用</b>（{@code Bash(*)}，以及等价的裸工具名 {@code Bash}）；</li>
     *   <li>路径目标上的<b>全盘 glob</b>：{@code **} / {@code /**} / {@code **}{@code /*} / {@code ~/**} /
     *       {@code {**,src}} …… 实测这些一律命中 {@code /etc/passwd} 与 {@code ~/.ssh/id_rsa}，
     *       与 {@code Write(*)} 的杀伤面完全一致，只是换了个写法躲开前两条判据。
     *       这里<b>不</b>枚举写法，而是拿 {@link #COVERAGE_CANARIES} 真跑一遍匹配——
     *       glob 的等价写法穷举不完，行为探测才穷举得完。</li>
     * </ol>
     *
     * <p>前两条正是计划给的判据，也都<b>不是</b>「允许，永久」的 {@code suggest()} 会生成的形态
     * （它只生成收窄的前缀 / 路径规则），故拒绝它们不影响审批工作流，也不影响团队共享
     * {@code {"allow":["Bash(mvn test:*)"]}} 这类合理配置。
     */
    private static boolean isBlanketAllow(PermissionRule r) {
        if ("*".equals(r.toolName()) || r.pattern() == null) {
            return true;
        }
        // 只有路径目标才走 glob 匹配；非路径目标（Bash 的 command、MCP 的整串入参）上
        // "**" 只是一个永不命中的字面量，不构成放行
        if (!ToolRegistry.lookup(r.toolName()).pathTarget()) {
            return false;
        }
        for (String canary : COVERAGE_CANARIES) {
            // root 传 null：相对模式（src/**）不做相对化，故只有真正的全盘 glob 才命中
            if (PermissionRule.globMatches(r.pattern(), canary, null)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 工具名对 {@link ToolRegistry} 交叉校验，命中不到就记 WARN。
     *
     * <p>{@code Bahs(rm:*)}（拼错）、{@code bash(rm:*)}（小写）都能通过 DSL 语法解析，
     * 却是<b>永久失效的死规则</b>——写成 deny 时尤其危险。
     * MCP 工具（{@code mcp__server__tool}）天然不在登记表里，不报。
     */
    private static void warnUnknownTool(PermissionRule r, String dsl) {
        String tool = r.toolName();
        if ("*".equals(tool) || tool.startsWith("mcp__")
                || ToolRegistry.registeredNames().contains(tool)) {
            return;
        }
        log.warn("权限规则 \"{}\" 的工具名 \"{}\" 不在已知工具里，这条规则永远不会命中"
                        + "（工具名区分大小写，如 Bash / Read / Write；MCP 工具形如 mcp__<server>__<tool>）。",
                dsl, tool);
    }

    /**
     * 解析某一层的 defaultMode；缺失、未知或不许由配置声明的取值一律返回 {@code null}。
     *
     * <p>{@link PermissionMode#BYPASS} 属于最后一种：见类注释「为什么配置文件不能声明 BYPASS」。
     */
    private static PermissionMode parseMode(JsonNode root, RuleScope scope) {
        if (root == null) {
            return null;
        }
        JsonNode n = root.get("defaultMode");
        if (n == null) {
            return null;
        }
        if (!n.isString()) {
            log.warn("defaultMode 不是字符串，回退默认模式：{}", n);
            return null;
        }
        String raw = n.stringValue().trim();
        PermissionMode mode = null;
        for (PermissionMode m : PermissionMode.values()) {
            if (m.name().equalsIgnoreCase(raw)) {
                mode = m;
                break;
            }
        }
        // 兼容 Claude Code 风格的小驼峰写法
        if (mode == null && "acceptEdits".equalsIgnoreCase(raw)) {
            mode = PermissionMode.ACCEPT_EDITS;
        }
        if (mode == null) {
            log.warn("未知 defaultMode='{}'（{} 层），回退默认模式。可选值：DEFAULT / ACCEPT_EDITS。", raw, scope);
            return null;
        }
        if (mode == PermissionMode.BYPASS) {
            log.warn("配置文件里的 defaultMode=\"BYPASS\"（{} 层）已忽略——跳过权限检查只能由命令行 "
                    + "--dangerously-skip-permissions 开启，不接受配置文件提权。", scope);
            return null;
        }
        return mode;
    }
}

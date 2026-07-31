package io.github.javaside.springai.codetui.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取两层 {@code permissions.json}（用户级 {@code ~/.codetui/permissions.json} +
 * 项目级 {@code <root>/.codetui/permissions.json}），合并为一份 {@link PermissionConfig}。
 *
 * <p><b>合并而非覆盖</b>：两层的 allow/ask/deny 全部保留并集——deny 只增不减，
 * 项目层<b>不能</b>削弱用户层的禁令。仅 {@code defaultMode} 是项目层覆盖用户层。
 *
 * <p><b>降级契约</b>（照 {@code McpConfigLoader}）：文件缺失 / JSON 非法 / 单条规则非法 /
 * {@code defaultMode} 未知 → 记 WARN、跳过、<b>绝不抛异常</b>。
 * 权限层挂了会把整个 agent 一起带走，比没有权限层更糟。
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
 */
public final class PermissionConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PermissionConfigLoader() {
    }

    /** 生产入口：由项目根解析出两层文件路径后加载。 */
    public static PermissionConfig load(Path root) {
        return load(userFile(), projectFile(root));
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

        // defaultMode：项目层覆盖用户层；项目层非法时回落用户层，两层都没有/都非法则 DEFAULT
        PermissionMode mode = parseMode(projectRoot);
        if (mode == null) {
            mode = parseMode(userRoot);
        }
        return new PermissionConfig(mode == null ? PermissionMode.DEFAULT : mode, List.copyOf(rules));
    }

    private static JsonNode readTree(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return MAPPER.readTree(Files.readString(file));
        } catch (Exception e) {
            log.warn("权限配置解析失败，忽略整个文件：{}（{}）", file, e.getMessage());
            return null;
        }
    }

    private static void collect(JsonNode root, RuleScope scope, List<PermissionRule> out) {
        if (root == null) {
            return;
        }
        add(root.get("deny"), PermissionBehavior.DENY, scope, out);
        add(root.get("ask"), PermissionBehavior.ASK, scope, out);
        add(root.get("allow"), PermissionBehavior.ALLOW, scope, out);
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
            PermissionRule r = PermissionRule.parse(n.stringValue(), behavior, scope);
            if (r == null) {
                // parse 返回 null 即语法非法（括号不闭合 / 空工具名 / 空括号），绝不能让 null 进列表
                log.warn("权限规则语法非法，跳过：{}（形如 \"Read(*)\" / \"Bash(git push:*)\"）",
                        n.stringValue());
                continue;
            }
            out.add(r);
        }
    }

    /**
     * 解析 defaultMode；缺失、未知或不许由配置声明的取值一律返回 {@code null}（调用方回落下一层）。
     *
     * <p>{@link PermissionMode#BYPASS} 属于最后一种：见类注释「为什么配置文件不能声明 BYPASS」。
     */
    private static PermissionMode parseMode(JsonNode root) {
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
            log.warn("未知 defaultMode='{}'，回退默认模式。可选值：DEFAULT / ACCEPT_EDITS。", raw);
            return null;
        }
        if (mode == PermissionMode.BYPASS) {
            log.warn("配置文件里的 defaultMode=\"BYPASS\" 已忽略——跳过权限检查只能由命令行 "
                    + "--dangerously-skip-permissions 开启，不接受配置文件提权。");
            return null;
        }
        return mode;
    }
}

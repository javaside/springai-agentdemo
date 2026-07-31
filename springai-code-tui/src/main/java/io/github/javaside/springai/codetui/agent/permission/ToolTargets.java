package io.github.javaside.springai.codetui.agent.permission;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 从工具入参 JSON 里抽出「判定目标」——面板显示 + 规则匹配 + 危险检查都用它。
 *
 * <p><b>降级契约</b>：JSON 非法 / 字段缺失 / 字段不是字符串 / 目标为空白 → 返回 {@code null}，
 * <b>绝不因入参内容抛异常</b>。目标为 null 时引擎按「无法核实」处理（带 pattern 的规则不命中，
 * 落到模式默认/兜底 ASK）。唯一的例外是 {@code root} 漏传——那是调用方的编程错误，
 * 不是模型给的脏数据，必须立即失败（见 {@link #extract(String, String, Path)}）。
 *
 * <p><b>UNKNOWN 工具</b>（含全部 MCP 工具）无登记字段，返回<b>整串入参</b>——
 * 面板上原样展示给人看，规则也可用 {@code 工具名(整串)} 精确放行。
 *
 * <p><b>路径目标已规范化</b>（{@link ToolRegistry.Entry#pathTarget()} 为真时）：
 * 这是 {@code PermissionRule} 契约里那句「由 ToolTargets 负责交出已规范化的路径」的落点，
 * 详见 {@link #resolvePath}。
 */
public final class ToolTargets {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolTargets() {
    }

    /**
     * 抽出判定目标。
     *
     * @param toolName  工具注册名
     * @param toolInput 入参 JSON
     * @param root      项目根，<b>不可为 null</b>：路径目标按它解析相对路径。
     *                  漏传会让全部路径规则静默失效（deny 被相对路径绕过、且无任何日志），
     *                  故此处直接 {@code NullPointerException} 立即失败，而不是少一层防护
     * @return 判定目标；无法核实一律 {@code null}
     * @throws NullPointerException root 为 null
     */
    public static String extract(String toolName, String toolInput, Path root) {
        Objects.requireNonNull(root, "root 不可为 null：漏传会让全部路径规则静默失效");
        return doExtract(toolName, toolInput, root);
    }

    /** 无根路径：仅供同包单测验证「不依赖 root 的那部分行为」，生产代码一律走三参重载。 */
    static String extract(String toolName, String toolInput) {
        return doExtract(toolName, toolInput, null);
    }

    private static String doExtract(String toolName, String toolInput, Path root) {
        ToolRegistry.Entry entry = ToolRegistry.lookup(toolName);
        if (entry.category() == ToolCategory.UNKNOWN) {
            return toolInput;                       // 未登记：整串入参即目标
        }
        if (entry.targetField() == null || toolInput == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(toolInput);
            JsonNode v = node.get(entry.targetField());
            // Jackson 3 的 asString() 对非文本节点会抛，必须先判 isString
            if (v == null || !v.isString()) {
                return null;
            }
            String raw = v.stringValue();
            if (raw.isBlank()) {
                return null;                        // 空目标无法核实 → 引擎落保守 ASK
            }
            return entry.pathTarget() ? resolvePath(raw, root) : raw;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 把路径目标解析成<b>可判定</b>的形式：相对路径按项目根解析，再 {@code normalize()}。
     *
     * <p><b>为什么必须在这里做</b>：{@code PermissionRule.globMatches} 只 {@code normalize()}，
     * 而 {@code Path.of("../../etc/passwd").normalize()} 是<b>空操作</b>——相对路径的 {@code ..}
     * 只有相对某个基准解析后才消得掉。不做这一步，deny 规则形同虚设（已实测）：
     * <pre>
     * deny /etc/**  vs /etc/passwd            → true
     * deny /etc/**  vs ../../../../etc/passwd → false   ← 同一个文件，deny 被绕过
     * deny **&#47;.env vs .env                    → false
     * </pre>
     * 而相对路径确实会到达工具：{@code AgentTools} 构造 {@code FileSystemTools} 时<b>不设</b>
     * {@code allowedDirectory}（空列表即放行任何路径），JVM 工作目录又恰是项目根，
     * 故 {@code ../../etc/passwd} 会落到 root 之外的真实文件上。
     *
     * <p>仍<b>只</b> {@code normalize()}、不 {@code toRealPath()}：后者会让「写一个尚不存在的文件」
     * 直接失败。符号链接不解析这一点，仍按 {@code PermissionRule} 的契约由 deny 规则与
     * {@code DangerousPaths} 兜。{@code ~/…} 不展开——{@code FileSystemTools} 也不展开，
     * 保持一致则它只是解析不到文件，而不会变成一条逃逸路径。
     */
    private static String resolvePath(String raw, Path root) {
        try {
            Path p = Path.of(raw);
            if (!p.isAbsolute() && root != null) {
                p = root.resolve(p);
            }
            return p.normalize().toString();
        } catch (RuntimeException e) {
            return null;                            // 非法路径字符 → 无法核实
        }
    }
}

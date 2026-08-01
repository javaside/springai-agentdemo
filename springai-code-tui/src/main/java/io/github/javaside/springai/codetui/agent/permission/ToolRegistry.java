package io.github.javaside.springai.codetui.agent.permission;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具登记表：注册名 → 类别 + 判定目标字段。
 *
 * <p><b>键必须是 {@code @Tool} 注解里的注册名，不是 Java 方法名</b>（全部经源码核实）。
 * 未登记的工具（含<b>全部 MCP 工具</b>）兜底为 {@link ToolCategory#UNKNOWN} → 保守 ASK。
 *
 * <p><b>记忆工具为何是 INTERNAL</b>：{@code AutoMemoryTools.resolveSafePath} 拒绝绝对路径并对
 * {@code normalize()} 后的结果做 {@code startsWith(memoriesDir)} 校验，路径已被库侧钳死在
 * {@code <root>/.codetui/memory} 内，再判一次没有意义。这不是漏登记。
 *
 * <p>完整性由 {@code ToolRegistryCompletenessTest} 对运行时工具集全量比对保证：
 * 存在但未登记的工具直接让测试失败。
 */
public final class ToolRegistry {

    /**
     * 一条登记。
     *
     * @param name        工具注册名
     * @param category    类别（决定模式默认行为）
     * @param targetField 判定目标取自入参 JSON 的哪个字段；null = 无目标字段
     * @param pathTarget  目标是否是文件路径（决定规则匹配走 glob 还是整串相等，以及是否做危险路径检查）
     */
    public record Entry(String name, ToolCategory category, String targetField, boolean pathTarget) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private static void put(String name, ToolCategory cat, String field, boolean path) {
        ENTRIES.put(name, new Entry(name, cat, field, path));
    }

    static {
        // ── 只读 ──
        put("Read",        ToolCategory.READ_ONLY,    "filePath", true);
        put("Grep",        ToolCategory.READ_ONLY,    "path",     true);
        put("Glob",        ToolCategory.READ_ONLY,    "path",     true);
        put("BashOutput",  ToolCategory.READ_ONLY,    "bash_id",  false);   // 只查自己起的进程
        put("KillShell",   ToolCategory.READ_ONLY,    "bash_id",  false);   // 只杀自己起的进程
        // ── 文件写 ──
        put("Write",       ToolCategory.FILE_WRITE,   "filePath", true);
        put("Edit",        ToolCategory.FILE_WRITE,   "filePath", true);
        // ── 命令 ──
        put("Bash",        ToolCategory.COMMAND,      "command",  false);
        // ── 只读网络 ──
        put("WebFetch",        ToolCategory.NETWORK_READ, "url",   false);
        put("BochaWebSearch",  ToolCategory.NETWORK_READ, "query", false);
        put("BraveWebSearch",  ToolCategory.NETWORK_READ, "query", false);
        // ── 内部（无外部副作用） ──
        put("TodoWrite",           ToolCategory.INTERNAL, null, false);
        put("Skill",               ToolCategory.INTERNAL, null, false);
        put("AskUserQuestionTool", ToolCategory.INTERNAL, null, false);
        put("Task",                ToolCategory.INTERNAL, null, false);   // 委派本身无害，拦的是子 agent 内部那份工具
        put("ParallelTasks",       ToolCategory.INTERNAL, null, false);
        put("ExitPlanMode",        ToolCategory.INTERNAL, null, false);   // 提交计划本身无副作用，PLAN 下必须放行
        // ── 记忆工具：路径已被库侧钳在 memories root 内（见类注释） ──
        put("MemoryView",       ToolCategory.INTERNAL, "path", false);
        put("MemoryCreate",     ToolCategory.INTERNAL, "path", false);
        put("MemoryStrReplace", ToolCategory.INTERNAL, "path", false);
        put("MemoryInsert",     ToolCategory.INTERNAL, "path", false);
        put("MemoryDelete",     ToolCategory.INTERNAL, "path", false);
        put("MemoryRename",     ToolCategory.INTERNAL, "oldPath", false);   // 入参是 oldPath/newPath，没有 path
    }

    private ToolRegistry() {
    }

    /** 查登记；未登记返回 UNKNOWN 兜底条目（目标字段为 null，判定目标改用整串入参）。 */
    public static Entry lookup(String toolName) {
        Entry e = ENTRIES.get(toolName);
        return e != null ? e : new Entry(toolName, ToolCategory.UNKNOWN, null, false);
    }

    /** 已登记的全部工具名（完整性测试用）。 */
    public static Set<String> registeredNames() {
        return Set.copyOf(ENTRIES.keySet());
    }
}

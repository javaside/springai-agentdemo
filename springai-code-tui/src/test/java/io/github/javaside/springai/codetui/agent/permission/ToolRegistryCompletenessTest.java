package io.github.javaside.springai.codetui.agent.permission;

import io.github.javaside.springai.codetui.agent.RuntimeToolSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登记表防漂移：拿 {@link ToolRegistry} 对着<b>运行时真实工具集</b>比对。
 *
 * <p>登记表漏一个工具 = 该工具悄悄落进 {@link ToolCategory#UNKNOWN}——轻则每次调用都弹审批，
 * 重则某天类别判错而静默放行。这个测试是登记表唯一的防漂移手段，故它比对的必须是
 * {@code AgentTools.build()} 真装配出来的那份工具集（见 {@link RuntimeToolSet}），
 * 而不是测试里另抄的一份名字清单——抄一份的话，新加的工具两边都缺，测试照样全绿。
 *
 * <p>三条一起钉住，少一条都能让一类错误溜过去：
 * <ol>
 *   <li><b>没漏登记</b>：运行时有的工具，登记表里都得有；</li>
 *   <li><b>没有僵尸条目</b>：登记表里的名字，运行时都得真存在（工具被删/改名时报错）；</li>
 *   <li><b>目标字段真存在</b>：每条登记的 {@code targetField} 得在该工具的入参 schema 里查得到。</li>
 * </ol>
 * 第 3 条是拿真事故换来的：{@code MemoryRename} 曾登记成字段 {@code path}，而它的实际入参是
 * {@code oldPath}/{@code newPath}，判定目标恒为 null、面板上一片空白，而只比对工具名的测试一个也抓不到。
 *
 * <p><b>比对用注册名（{@code getToolDefinition().name()}），不是 Java 方法名</b>：二者会不一样，
 * 例如 {@code @Tool(name = "AskUserQuestionTool")} 挂在方法 {@code askUserQuestion} 上，
 * 而库版 {@code WebSearch} 被本项目 {@code RenamedToolCallback} 改成了 {@code BraveWebSearch}。
 *
 * <p><b>MCP 工具不比对</b>：它们运行期才知道，兜底 UNKNOWN→ASK 就是为它们准备的。
 */
class ToolRegistryCompletenessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("每个内置工具都在权限登记表里（漏登记即失败）")
    void everyBuiltinToolIsRegistered(@TempDir Path root) {
        var runtimeNames = RuntimeToolSet.byRegisteredName(root).keySet();
        var registered = ToolRegistry.registeredNames();

        var missing = new TreeSet<>(runtimeNames);
        missing.removeAll(registered);

        assertTrue(missing.isEmpty(),
                "以下工具未登记进 ToolRegistry（会落进 UNKNOWN→每次都弹审批）：" + missing
                        + "\n已登记：" + new TreeSet<>(registered));
    }

    @Test
    @DisplayName("登记表里没有已不存在的僵尸条目")
    void noStaleEntries(@TempDir Path root) {
        // 反向：登记表里的名字必须都能在运行时工具集里找到，否则说明工具被删了或改了名——
        // 改名尤其危险：老名字的规则还在，新名字却落进 UNKNOWN。
        var runtimeNames = RuntimeToolSet.byRegisteredName(root).keySet();

        var stale = new TreeSet<>(ToolRegistry.registeredNames());
        stale.removeAll(runtimeNames);

        assertTrue(stale.isEmpty(),
                "登记表存在已不再注册的僵尸条目：" + stale
                        + "\n运行时工具：" + new TreeSet<>(runtimeNames));
    }

    /**
     * 每条登记的判定目标字段都得真在该工具的入参 schema 里。
     *
     * <p><b>只能逐字比对字面量，不能靠命名风格推断</b>：同一个库类里 {@code Read}/{@code Write} 用
     * {@code filePath}（驼峰），而 {@code Edit} 用 {@code old_string}（蛇形），驼峰/蛇形互转必猜错。
     */
    @Test
    @DisplayName("每条登记的 targetField 都存在于该工具的入参 schema 里")
    void everyTargetFieldExistsInSchema(@TempDir Path root) {
        Map<String, ToolCallback> runtime = RuntimeToolSet.byRegisteredName(root);
        List<String> bad = new ArrayList<>();

        for (String name : new TreeSet<>(ToolRegistry.registeredNames())) {
            String field = ToolRegistry.lookup(name).targetField();
            if (field == null) {
                continue;   // 无目标字段（INTERNAL 类多为此），判定目标改用整串入参
            }
            ToolCallback callback = runtime.get(name);
            if (callback == null) {
                continue;   // 僵尸条目由 noStaleEntries 报，这里不重复报
            }
            var properties = schemaProperties(callback);
            if (!properties.contains(field)) {
                bad.add(name + ".targetField=\"" + field + "\" 不在入参里，实际入参=" + properties);
            }
        }

        assertTrue(bad.isEmpty(),
                "以下登记的目标字段在工具入参 schema 里不存在（提取恒为 null，判定目标一片空白）：\n"
                        + String.join("\n", bad));
    }

    /** 取工具入参 schema 的顶层属性名（有序，报错信息才稳定可读）。 */
    private static TreeSet<String> schemaProperties(ToolCallback callback) {
        JsonNode schema = MAPPER.readTree(callback.getToolDefinition().inputSchema());
        JsonNode properties = schema.get("properties");
        var names = new TreeSet<String>();
        if (properties != null) {
            properties.propertyNames().forEach(names::add);
        }
        return names;
    }
}

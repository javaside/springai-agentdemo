package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试专用：枚举<b>本应用真会注册的内置工具</b>，取的是 {@link AgentTools#build} 装配出来的那一份，
 * 而不是在测试里重新拼一遍。
 *
 * <p><b>为什么必须从 build() 里掏</b>：登记表完整性测试的价值全在「有人给 AgentTools 加了工具、
 * 却忘了补登记表」这一刻能变红。若测试自己另拼一份工具集，新加的工具两边都没有，测试照样全绿——
 * 它就只是在自证自洽，一点漂移也抓不到。故这里从装配产物 {@code ChatClient} 上取回真实
 * {@link ToolCallback} 列表（{@code DefaultChatClientRequestSpec.getToolCallbacks()}），
 * 让「运行时工具集」这个概念只有一个来源。
 *
 * <p><b>两个 env 门控的搜索工具单独补</b>：{@code BochaWebSearch} / {@code BraveWebSearch} 只在
 * 配了各自 API key 时才进 build()，无 key 的机器（含 CI）上装配产物里根本没有它们。
 * 但登记表必须常备这两条，否则配了 key 的机器上它们会落进 UNKNOWN。故这里用
 * {@code AgentTools} 自己那两个工厂方法拿假 key 各造一个补进来——仍是同一套构造逻辑，不是另抄一份。
 *
 * <p><b>MCP 工具刻意不在内</b>：它们运行期才知道，兜底 UNKNOWN→ASK 就是为它们准备的，
 * 故 build() 传 {@code mcpRegistry = null}。
 */
public final class RuntimeToolSet {

    private RuntimeToolSet() {
    }

    /**
     * 装配一次并返回全部内置工具的 callback（按注册名去重，键为注册名）。
     * 全程离线：假 key 的 DeepSeek provider，build() 只装配不发请求。
     *
     * @param root 项目根（工具工作目录 + 会话/记忆落盘基准），传 {@code @TempDir} 即可
     */
    public static Map<String, ToolCallback> byRegisteredName(Path root) {
        List<ToolCallback> all = new ArrayList<>(assembledTools(root));

        // env 门控的两个搜索工具：用 AgentTools 自己的工厂喂假 key 造出来，保证与生产同一套构造逻辑。
        BochaWebSearchTool bocha = AgentTools.createWebSearchTool("fake-key", null);
        if (bocha != null) {
            all.add(ToolCallbacks.from(bocha)[0]);
        }
        ToolCallback brave = AgentTools.createBraveWebSearchTool("fake-key", null);
        if (brave != null) {
            all.add(brave);
        }

        // 去重：机器上真配了 BOCHA/BRAVE key 时，build() 里已有一份，上面又补了一份。
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback c : all) {
            byName.putIfAbsent(c.getToolDefinition().name(), c);
        }
        return byName;
    }

    /** 从 {@link AgentTools#build} 产出的 ChatClient 上取回它实际注册的 defaultTools。 */
    private static List<ToolCallback> assembledTools(Path root) {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        ChatClient client = AgentTools.build(registry, root, new StubListener()).client();

        // prompt() 复制一份 default request spec，defaultTools 原样带过来；这是取回装配期工具列表的唯一公开入口。
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (!(spec instanceof DefaultChatClient.DefaultChatClientRequestSpec defaultSpec)) {
            throw new IllegalStateException(
                    "取不到装配期工具列表：ChatClient 实现已不是 DefaultChatClient（实际 spec="
                            + spec.getClass().getName() + "）。"
                            + "这会让登记表完整性测试失去唯一的真实来源，必须先修好枚举方式，不能改成手抄工具名。");
        }
        return defaultSpec.getToolCallbacks();
    }
}

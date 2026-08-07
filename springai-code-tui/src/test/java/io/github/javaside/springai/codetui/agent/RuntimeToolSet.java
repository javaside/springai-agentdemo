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
 * <p><b>这条补齐带一个已知缺口</b>：两个搜索工具是<b>无条件</b>补进来的，故它们豁免于
 * 「僵尸条目」检查——实测把 {@code AgentTools.build()} 里的 {@code if (webSearch != null)} 整段删掉
 * （工具真的从产品里消失了），完整性测试仍 3/3 全绿。实践中这个洞很窄：改名仍会被抓到，
 * 删掉工厂方法本身是编译错误，只有「删调用点却留着工厂」这一种改法会漏。
 * 换来的是无 key 机器（CI）与有 key 机器枚举一致，这个取舍值得——但别把本 helper
 * 读成「搜索工具的存在性也被钉住了」。
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

    /**
     * 从 {@link AgentTools#build} 产出的 ChatClient 上取回它实际注册的 defaultTools。
     *
     * <p><b>为什么还要断言另一个通道为空</b>：{@code getToolCallbacks()} 只是工具注册通道之一，
     * 兄弟是 {@code getToolCallbackProviders()}（由 {@code defaultToolCallbacks(ToolCallbackProvider...)} 填充）。
     * 今天 {@code AgentTools} 只用 {@code .defaultTools(...)}，全部落进 toolCallbacks，故枚举完整；
     * 但换通道注册的那天，本 helper 会<b>静默少枚举</b>、整个完整性套件空转全绿——
     * 正是它要防的那种失效，且完全不可见。故宁可炸。
     *
     * <p><b>第三个通道在本项目不存在，别照 1.x 文档补断言</b>：{@code defaultToolNames(String...)} /
     * {@code getToolNames()} 是 Spring AI <b>1.x</b> 的通道，2.0.0 已删除该 API。
     * 本项目解析到的是 2.0.0（{@code pom.xml} 的 {@code spring-ai.version}，经
     * {@code mvn -pl springai-code-tui dependency:tree -Dincludes=org.springframework.ai:spring-ai-client-chat}
     * 确认为 {@code spring-ai-client-chat:jar:2.0.0:compile}），故本方法只断言上面那一个兄弟通道。
     *
     * <p><b>核对时务必认准解析到的那个 jar</b>：{@code ~/.m2} 里同时缓存着 1.0.x / 1.1.x / 2.0.0 十几个版本
     * （多是别的项目留下的），随手 {@code javap} 一个 1.1.4 的 jar 会看到 {@code getToolNames()} 赫然在列，
     * 从而得出「三个通道都在」的相反结论。最不会看错的判据是拿真实 classpath 编一下：
     * {@code javac -cp <test-classpath> } 一句 {@code s.getToolNames();} 在本项目报
     * 「找不到符号: 方法 getToolNames()」——即该通道根本写不出来，无从断言。
     */
    private static List<ToolCallback> assembledTools(Path root) {
        ProviderRegistry registry = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        return toolsOf(AgentTools.build(registry, root, new StubListener()).client());
    }

    /**
     * 从<b>已经建好</b>的 runtime 上取回同一份工具列表。
     *
     * <p>与 {@link #byRegisteredName} 的区别只在于「谁来 build」：需要把工具与该次装配的<b>其他</b>
     * 产物（如 {@code interjections()} / {@code backgroundRegistry()}）对上号时，必须是同一次 build——
     * 各建一次的话拿到的是两个互不相干的对象，测试会永远绿，而它想验的正是「这两个是同一个」。
     */
    public static Map<String, ToolCallback> toolsOf(AgentTools.AgentRuntime runtime) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback c : toolsOf(runtime.client())) {
            byName.putIfAbsent(c.getToolDefinition().name(), c);
        }
        return byName;
    }

    private static List<ToolCallback> toolsOf(ChatClient client) {
        // prompt() 复制一份 default request spec，defaultTools 原样带过来；这是取回装配期工具列表的唯一公开入口。
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (!(spec instanceof DefaultChatClient.DefaultChatClientRequestSpec defaultSpec)) {
            throw new IllegalStateException(
                    "取不到装配期工具列表：ChatClient 实现已不是 DefaultChatClient（实际 spec="
                            + spec.getClass().getName() + "）。"
                            + "这会让登记表完整性测试失去唯一的真实来源，必须先修好枚举方式，不能改成手抄工具名。");
        }
        if (!defaultSpec.getToolCallbackProviders().isEmpty()) {
            throw new IllegalStateException(
                    "有工具改走 ToolCallbackProvider 通道注册了（provider 数="
                            + defaultSpec.getToolCallbackProviders().size()
                            + "），本 helper 只枚举 getToolCallbacks()，会静默少枚举——"
                            + "必须同步扩展本方法把 provider 里的工具也展开，否则完整性测试形同虚设。");
        }
        return defaultSpec.getToolCallbacks();
    }
}

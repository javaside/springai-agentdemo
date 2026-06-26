package com.example.springai.agent.demo;

import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 示例 6：Skill（技能）能力 —— 用第三方社区库 spring-ai-agent-utils 的 {@link SkillsTool}。
 *
 * <p><b>什么是 Skill？</b>Skill = 一个含 <code>SKILL.md</code> 的文件夹，
 * SKILL.md 由「YAML frontmatter（name + description）」+「正文指令」两部分组成，
 * 是一份<b>可复用的“操作说明书 / 领域知识模块”</b>。比如本示例的 <code>git-commit-message</code>
 * 技能，就封装了“如何按 Conventional Commits 规范写提交信息”的完整规则。
 *
 * <p><b>它和普通 @Tool 工具有什么不同？</b>
 * <ul>
 *   <li>普通工具：执行一段 Java 代码，返回数据（如查天气、算日期）。</li>
 *   <li>Skill 工具：本质是“按需把一大段<b>指令/提示词</b>注入对话”。模型平时只看到技能的简短
 *       name + description（很省 token）；当它判断某个技能有用时，<b>调用名为 <code>Skill</code>
 *       的工具、只传技能名</b>，工具便返回该 SKILL.md 的<b>完整正文</b>；模型读到这些详细指令后，
 *       再按其要求产出结果。这是一种“渐进式披露（progressive disclosure）”的提示词工程。</li>
 * </ul>
 *
 * <p><b>执行流程（本示例会把每一步打印出来）：</b>
 * <ol>
 *   <li>从 <code>skills/</code> 目录加载所有 SKILL.md，构建出一个名为 <code>Skill</code> 的 {@link ToolCallback}；</li>
 *   <li>第 1 轮：模型只看到“可用技能清单（仅名字+描述）”，决定调用 <code>Skill("git-commit-message")</code>；</li>
 *   <li>工具返回该技能 SKILL.md 的完整正文（基目录 + 指令）；</li>
 *   <li>第 2 轮：模型读完指令，按 Conventional Commits 规范产出最终提交信息。</li>
 * </ol>
 *
 * <p>为把“技能被发现 → 被调用 → 返回了什么 → 模型据此作答”看清楚，示例用两个装饰器/拦截器：
 * {@link LoggingSkillCallback} 打印技能工具的输入/输出，{@link RoundLoggingAdvisor} 按轮次打印交互过程
 * （均用 {@code System.out}，不走日志框架，输出整齐）。
 */
public class SkillToolDemo implements Demo {

    private final ChatClient chatClient;

    public SkillToolDemo(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String title() {
        return "Skill 技能（SkillsTool · 第三方 spring-ai-agent-utils）";
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public void run() {
        // 1) 定位 skills/ 目录。运行方式不同，工作目录也不同（IDE 里可能是模块目录，命令行里常是仓库根目录），
        //    所以这里依次尝试几个候选路径，取第一个真实存在的。
        String skillsDir = resolveSkillsDir();
        if (skillsDir == null) {
            System.out.println("⚠️  没找到 skills/ 目录，跳过本示例。请在仓库根目录或 springai-agent-demo 目录下运行。");
            return;
        }
        System.out.println("📂 技能目录: " + skillsDir);

        // 2) 用 SkillsTool 把该目录下的 SKILL.md 们构建成一个 ToolCallback（工具名固定为 "Skill"）。
        //    再包一层 LoggingSkillCallback，把“模型调用了哪个技能、技能返回了什么正文”打印出来。
        ToolCallback skillTool = new LoggingSkillCallback(
                SkillsTool.builder()
                        .addSkillsDirectory(skillsDir)
                        .build());

        System.out.println("🧰 已加载技能工具: " + skillTool.getToolDefinition().name()
                + "（模型一开始只能看到各技能的【名字+描述】，看不到正文，很省 token）");

        // 3) 轮次日志：放在工具调用 advisor 的【内层】（order 更大），拦截工具循环里的每一轮。
        RoundLoggingAdvisor roundLogger = new RoundLoggingAdvisor(ToolCallingAdvisor.DEFAULT_ORDER + 100);

        String question = "帮我给这次改动写一条规范的 git 提交信息：给登录接口加了图形验证码，"
                + "并修复了验证码不区分大小写的问题。";
        System.out.println("\n问：" + question + "\n");

        String answer = chatClient.prompt()
                .user(question)
                // 像注册普通工具一样把技能工具交给模型；剩下的“发现→调用→注入正文”由 SkillsTool 负责。
                .tools(skillTool)
                .advisors(roundLogger)
                .call()
                .content();

        System.out.println("\n答：\n" + answer);
        System.out.println("""

                说明：从轮次可见——第 1 轮模型只凭“技能描述”就决定调用 Skill 工具并传入技能名
                （git-commit-message），工具随即返回该 SKILL.md 的【完整指令正文】；第 2 轮模型读完指令，
                才按 Conventional Commits 规范写出最终提交信息。这就是 Skill 的价值：把详细的领域指令
                “按需”注入对话，而不是一开始就塞满上下文。
                """);
    }

    /** 依次尝试候选路径，返回第一个存在且为目录的 skills 路径；都没有则返回 null。 */
    private static String resolveSkillsDir() {
        List<String> candidates = List.of(
                "springai-agent-demo/skills",  // 从仓库根目录运行（java -jar springai-agent-demo/target/...）
                "skills",                       // 从模块目录运行（IDE 工作目录 = 模块）
                "../springai-agent-demo/skills" // 从其它兄弟模块目录运行
        );
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    /**
     * 装饰器：包住真正的“Skill”工具 {@link ToolCallback}，在每次被模型调用时打印
     * 「调用了哪个技能」和「技能返回的正文（即 SKILL.md 内容）」，让技能的执行过程可见。
     * 其余元信息（工具定义/元数据）原样转发。
     */
    private static final class LoggingSkillCallback implements ToolCallback {

        private final ToolCallback delegate;

        LoggingSkillCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return logAround(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return logAround(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String logAround(String toolInput, java.util.function.Supplier<String> invocation) {
            System.out.println("  │ 🛠 技能工具被调用，入参: " + clean(toolInput));
            String result = invocation.get();
            System.out.println("  │ 📜 技能返回内容（SKILL.md 正文，节选）:");
            for (String line : truncate(result, 600).split("\n")) {
                System.out.println("  │     " + line);
            }
            return result;
        }
    }

    /**
     * 自定义 CallAdvisor：按“轮次”整齐打印每一轮“发给模型的可用工具 / 模型本轮的决定”。
     * 与 {@link ToolSearchDemo} 中的同名类作用一致——用 System.out，避免日志框架前缀打乱版面。
     */
    private static final class RoundLoggingAdvisor implements CallAdvisor {

        private final int order;
        private int round = 0;

        RoundLoggingAdvisor(int order) {
            this.order = order;
        }

        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            int current = ++round;
            System.out.println("  ┌─ 第 " + current + " 轮 ──────────────");
            System.out.println("  │ ↗ 本轮模型可用工具: " + availableTools(request));

            ChatClientResponse response = chain.nextCall(request);

            System.out.println("  │ ↘ 模型决定: " + decision(response));
            System.out.println("  └────────────────────────");
            return response;
        }

        @Override
        public String getName() {
            return "RoundLoggingAdvisor";
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        private static String availableTools(ChatClientRequest request) {
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions opts) {
                List<String> names = opts.getToolCallbacks().stream()
                        .map(tc -> tc.getToolDefinition().name())
                        .toList();
                if (!names.isEmpty()) {
                    return String.join(", ", names);
                }
            }
            return "（无）";
        }

        private static String decision(ChatClientResponse response) {
            ChatResponse cr = response.chatResponse();
            if (cr == null || cr.getResult() == null) {
                return "(空响应)";
            }
            AssistantMessage out = cr.getResult().getOutput();
            if (out.getToolCalls() != null && !out.getToolCalls().isEmpty()) {
                String calls = out.getToolCalls().stream()
                        .map(tc -> tc.name() + "(" + clean(tc.arguments()) + ")")
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                return "请求调用工具 → " + calls;
            }
            return "直接回答 → " + truncate(clean(out.getText()), 50);
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}

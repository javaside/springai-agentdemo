package com.example.springai.codetui;

import com.example.springai.codetui.agent.AgentTools;
import com.example.springai.codetui.agent.CodingAgent;
import com.example.springai.codetui.ui.CodeTuiView;
import com.example.springai.codetui.ui.ConversationState;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.chat.client.ChatClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * springai-code-tui 入口 —— 里程碑3：接入真实 {@link CodingAgent}，
 * 取代里程碑1/2 的回显桩与被动重绘自检探针。
 *
 * <p>里程碑4：在构建模型/TUI 之前加一道启动安全门（方案 B——诚实披露 + 确认门，
 * 不是技术强制沙箱）。见 {@link #isCleared(String, String)} 与 {@code README.md}。
 */
public class CodeTuiApplication {

    /** 放行环境变量：设为 "1" 可跳过交互确认（用于 CI / 已知情况下的重复运行）。 */
    private static final String ENV_UNDERSTAND = "CODE_TUI_I_UNDERSTAND";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        printSafetyBanner(root);

        String envUnderstand = System.getenv(ENV_UNDERSTAND);
        String interactiveAnswer = "1".equals(envUnderstand) ? null : readInteractiveAnswer();

        if (!isCleared(envUnderstand, interactiveAnswer)) {
            System.out.println("已取消。");
            return;
        }

        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("⚠️  未检测到 DEEPSEEK_API_KEY。请先：export DEEPSEEK_API_KEY=你的key 再运行。");
            return;
        }

        DeepSeekApi deepSeekApi = DeepSeekApi.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.deepseek.com")
                .build();

        // 模型名 "deepseek-v4-flash"（V4 现役；旧的 deepseek-chat/deepseek-reasoner 将于 2026-07-24 停用，
        // 期间被透明路由到 v4-flash）。显式设置，避免依赖会过期的默认名。
        DeepSeekChatModel model = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .options(DeepSeekChatOptions.builder()
                        .model("deepseek-v4-flash")
                        .build())
                .build();

        ConversationState state = new ConversationState();       // implements AgentListener
        AtomicLong activeTurnId = new AtomicLong();               // 交给 CodingAgent 生成 id
        String sessionId = "code-tui-session";                    // v1 单会话固定 id

        ChatClient client = AgentTools.build(model, root, state);
        CodingAgent agent = new CodingAgent(client, state, sessionId, activeTurnId);
        CodeTuiView view = new CodeTuiView(state, agent, root);   // root：diff 渲染时读原文件 + 相对化展示路径
        view.run();
    }

    /**
     * 打印红色 ANSI 警示横幅（在 TUI 启动前，走普通 stdout，不影响后续 alternate-screen）。
     * 明确列出真实的安全边界：只有 FileSystemTools 受 root 沙箱，Shell/Grep/Glob 不受限。
     */
    private static void printSafetyBanner(Path root) {
        String red = "[31m";
        String reset = "[0m";
        System.out.println(red + "================ ⚠️  安全警示（请阅读） ⚠️  ================" + reset);
        System.out.println(red + "工作区 root（仅 FileSystemTools 的沙箱边界）：" + root + reset);
        System.out.println(red + "本工具的智能体拥有以下能力："  + reset);
        System.out.println(red + "  - FileSystemTools：受上面这个 root 目录沙箱限制（越界会被拒绝）。" + reset);
        System.out.println(red + "  - ShellTools / GrepTool / GlobTool：不受 root 限制！" + reset);
        System.out.println(red + "    智能体可以借助它们执行任意 shell 命令、读写磁盘上任意位置的文件。" + reset);
        System.out.println(red + "建议：只在可以随意丢弃、且已被版本控制干净纳管的目录中运行本工具；" + reset);
        System.out.println(red + "     不要在 $HOME 或任何重要仓库的根目录下运行。" + reset);
        System.out.println(red + "=============================================================" + reset);
    }

    /**
     * 交互式读取用户确认（在 TUI/raw-mode 启动前，用普通 stdin 即可）。
     * stdin 为 null/已关闭/读取异常时视为「无输入」，交给 {@link #isCleared} 判定不放行。
     */
    private static String readInteractiveAnswer() {
        System.out.print("已理解上述风险并确认继续？[y/N] ");
        System.out.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 返回是否放行启动。
     *
     * @param envUnderstand    {@code CODE_TUI_I_UNDERSTAND} 环境变量值，"1" 即放行
     * @param interactiveAnswer 交互式输入，null 表示非交互/无输入；以 y/Y 开头（去除首尾空白后）即放行
     */
    static boolean isCleared(String envUnderstand, String interactiveAnswer) {
        if ("1".equals(envUnderstand)) {
            return true;
        }
        return interactiveAnswer != null && interactiveAnswer.trim().equalsIgnoreCase("y");
    }
}

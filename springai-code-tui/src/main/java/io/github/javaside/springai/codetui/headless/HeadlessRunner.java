package io.github.javaside.springai.codetui.headless;

import io.github.javaside.springai.codetui.CodeTuiApplication;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * headless 跑法：公共装配（headless 特化：不接 goal/todo 提醒线）→ MCP 等待 → submit →
 * await（总硬顶）→ stdout 最终回复 → 收尾报告。返回退出码；System.exit 留给 main（与 TUI 同纪律）。
 */
public final class HeadlessRunner {

    private HeadlessRunner() {}

    public static int run(String[] args, Path root, String sessionId, boolean resumed) {
        String promptText;
        try {
            promptText = resolvePrompt(args);   // 解析非法/stdin 空白在此抛（stderr + 退出 1，见 resolvePrompt）
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("错误：" + e.getMessage());
            return 1;
        }
        HeadlessAgentListener listener = new HeadlessAgentListener(System.err);
        CodeTuiApplication.Assembled a;
        try {
            a = CodeTuiApplication.assemble(args, root, listener, System.err::println, false, sessionId);
        } catch (Throwable t) {   // init 之后装配步骤抛异常会裸栈逃出（现 main 同窗口）；headless 卖点是脚本友好，stderr + 退出 1
            System.err.println("✗ 装配失败：" + t);
            return 1;
        }
        if (a == null) {
            // key 缺失：assemble 的 headless 分支已 System.err 打过中文文案（双模式统一 return null，退出权在调用方）
            return 1;
        }
        try {
            String notice = resumeNotice(resumed, a.wantContinue(), sessionId);
            if (notice != null) {
                System.err.println(notice);
            }
            // 零 enabled server 时 onMcpReady 不发（spec 事实 #6）——先查 connectingCount 再等
            long mcpWait = mcpWaitSeconds();
            if (a.mcpRegistry().connectingCount() > 0 && !listener.awaitMcpReady(mcpWait)) {
                System.err.println("⚠ MCP 连接等待超时（" + mcpWait + "s），工具可能缺席，继续执行。");
            }
            Disposable turn;
            try {
                turn = a.agent().submit(promptText);
            } catch (RuntimeException e) {   // submit 同步段会抛且无外层 catch（spec 事实/评审修订）
                System.err.println("✗ 提交失败：" + e);
                return 1;
            }
            listener.bindTurn(turn);
            long maxWait = maxSeconds();   // 总硬顶：空闲超时只杀静默流（spec 事实 #9）
            if (!listener.awaitDone(maxWait)) {
                turn.dispose();
                System.err.println("✗ headless 回合超时（CODETUI_HEADLESS_MAX_SECONDS=" + maxWait + "s）。");
                return 1;
            }
            String reply = listener.planText() != null ? listener.planText() : listener.finalReply();
            if (!reply.isBlank()) {
                System.out.println(reply);   // 空则不打（stdout 只有最终回复——exit-2 等空回复场景不出空行）
            }
            writeLastMessage(args, reply);
            var snap = a.usage().snapshot();
            System.err.println("（会话 id：" + sessionId + "；--resume " + sessionId + " 可续跑。）");
            System.err.println("（token：" + snap.promptTokens() + " in / " + snap.completionTokens() + " out）");
            return listener.exitCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("✗ headless 被中断。");
            return 1;
        } finally {
            a.mcpRegistry().close();   // 与 main 同序：先 MCP 后 goal 池
            a.goalRunner().close();
        }
    }

    /**
     * 解析提示词：{@code headlessPrompt} 只解析一次（run 的 submit 用同一份，勿再调 {@code prompt(args)} 二次解析）；
     * {@code "-"} 哨兵读全 stdin。
     *
     * <p><b>读 stdin 绝不 close</b>：{@code System.in} 是进程级单例、不归本次读取所有——真机冒烟的纪律是
     * 同 JVM 直调 {@code run()} 多次（HeadlessSmokeTest 链式两步），第一次就把流关了，第二次 {@code -p -}
     * 必抛 Stream closed。读尽即止，关闭留给 JVM。
     *
     * <p><b>stdin 空白与解析非法同路</b>：{@code echo "" | codetui -p -} 若照常 submit 空串，
     * 脚本拿到的是模型侧 400 而非「提示词为空」——与 {@code headlessPrompt} 拒绝 {@code -p "  "} 对称。
     * 读取失败（IOException）包装 ISE 一并交给 run 的同一段 catch（stderr + 退出 1）。
     */
    static String resolvePrompt(String[] args) {
        String spec = CodeTuiApplication.headlessPrompt(args);
        if (!"-".equals(spec)) {
            return spec;
        }
        String text;
        try {
            text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 stdin 失败", e);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("-p 从 stdin 读到的提示词为空。");
        }
        return text;
    }

    /**
     * 开场会话提示（stderr）：恢复 → 报会话 id；{@code -c} 但无可恢复 → <b>必须明说已开新会话</b>——
     * 脚本链式调用默认上下文在延续，静默新开会丢前文且无任何征兆（TUI 同分支有同款提示）；
     * 默认启动 → null（不打扰）。
     */
    static String resumeNotice(boolean resumed, boolean wantContinue, String sessionId) {
        if (resumed) {
            return "（已恢复会话 " + sessionId + "，历史进入上下文。）";
        }
        if (wantContinue) {
            return "（没有可恢复的会话，已开始新会话。）";
        }
        return null;
    }

    static long mcpWaitSeconds() {
        return clamp(System.getenv("CODETUI_HEADLESS_MCP_WAIT_SECONDS"), 10L, 1L, 300L);
    }

    static long maxSeconds() {
        return clamp(System.getenv("CODETUI_HEADLESS_MAX_SECONDS"), 3600L, 60L, 86400L);
    }

    private static long clamp(String raw, long def, long min, long max) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        try {
            return Math.max(min, Math.min(max, Long.parseLong(raw.strip())));
        } catch (NumberFormatException e) {
            return def;   // 非法回退默认，与 LlmTimeouts 同口径
        }
    }

    private static void writeLastMessage(String[] args, String reply) {
        String path = CodeTuiApplication.outputLastMessagePath(args);
        if (path == null) {
            return;
        }
        try {
            Files.writeString(Path.of(path), reply + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {   // 写失败只警告不改退出码（spec 定案）
            System.err.println("⚠ --output-last-message 写入失败（" + path + "）：" + e);
        }
    }
}

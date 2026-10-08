package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import reactor.core.Disposable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机测量：过期清单提醒在<b>真实模型回合</b>里会不会被触发、模型又会不会照着更新。
 *
 * <p><b>门控</b>：联网 + 花钱 + 墙钟依赖模型速度，故与 {@code CodingAgentSpikeTest} 同款双门控
 * （{@code CODETUI_LIVE_TESTS=1} + {@code DEEPSEEK_API_KEY}），默认不跑。
 * <pre>
 *   CODETUI_LIVE_TESTS=1 mvn -pl springai-code-tui -am test \
 *       -Dtest='TodoReminderLiveSpikeTest' -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p><b>本用例的立场</b>：硬断言只钉「机制」（真实模型建了清单 → 快照进了提醒器；
 * 模型真跑出一条提交 → 下一个调用确实被注入了提醒），因为这两件事可复现；
 * <b>模型是否照提醒更新是「测得的观察值」，只打印不硬断言</b>——模型行为天生有方差，
 * 断言它会让用例变 flaky，而它的价值恰恰在于把「响应率」这个数字量出来。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class TodoReminderLiveSpikeTest {

    private static final long TURN_TIMEOUT_MS = 180_000L;

    /** 与 {@code TodoStaleReminder.COMMIT} 同口径（那份是包私有，跨包测试读不到）。 */
    private static final java.util.regex.Pattern COMMIT = java.util.regex.Pattern.compile(
            "(?<![\\w-])git\\s+(?:(?:-[A-Za-z]|--[A-Za-z][A-Za-z-]*)(?:=\\S+|\\s+\\S+)?\\s+)*commit\\b");

    @TempDir Path root;

    /**
     * 宿主仓库根（本测试文件所在仓库）。
     *
     * <p><b>为什么需要护栏</b>：Bash 工具的工作目录是<b>进程 CWD</b>（= 宿主仓库），不是
     * {@code build(...)} 传的 {@code root}（那个只给 Grep/Glob/会话用）。所以真实模型完全可能
     * 「在真实仓库里」干活——实测第一次跑就把 hello.txt/world.txt 提交进了本仓库（事后已回退）。
     * 这里跑前记 HEAD、跑后比对，一旦被改就立刻失败，绝不留下静默污染。
     */
    private static final Path HOST_REPO = Path.of("").toAbsolutePath();

    private static String runHostGit(String... args) {
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
                    "git", "-C", HOST_REPO.toString()));
            cmd.addAll(java.util.List.of(args));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return out.trim();
        } catch (Exception e) {
            return "unavailable:" + e.getMessage();
        }
    }

    private static String hostHead() {
        try {
            Process p = new ProcessBuilder("git", "-C", HOST_REPO.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out;
        } catch (Exception e) {
            return "unavailable:" + e.getMessage();
        }
    }

    private static final class Recorder extends io.github.javaside.springai.codetui.agent.seam.StubListener {
        final List<String> toolOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> toolInputs = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Long> completed = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void onToolStarted(long turnId, String toolName, String input) {
            toolOrder.add(toolName);
            toolInputs.add(input);
        }
        @Override public void onPermissionRequested(long turnId,
                io.github.javaside.springai.codetui.agent.seam.PermissionRequest request) {
            request.responder().respond(io.github.javaside.springai.codetui.agent.seam.PermissionOutcome.ALLOW_ONCE);
        }
        @Override public void onTurnComplete(long turnId) { completed.add(turnId); }
        @Override public void onError(long turnId, Throwable error) { errors.add(error); }
    }

    @Test
    void reminderFiresInRealTurn_andModelResponseIsMeasured() throws Exception {
        new ProcessBuilder("git", "init", "-q").directory(root.toFile()).start().waitFor();
        Files.writeString(root.resolve("README.md"), "spike\n");
        String hostHeadBefore = hostHead();
        String hostStatusBefore = runHostGit("status", "--porcelain");

        Recorder rec = new Recorder();
        AtomicLong active = new AtomicLong();
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider(System.getenv("DEEPSEEK_API_KEY")))),
                root, rec);
        assertTrue(rt.todoReminder().enabled(), "提醒器须启用（检查 CODETUI_TODO_REMIND_EVERY 是否被显式设 0）");
        String sessionId = "todo-reminder-live-spike";
        CodingAgent agent = new CodingAgent(rt.client(), rec, sessionId, active,
                rt.sessionService(), rt.manualStrategy(), rt.tokenCountEstimator());

        // 刻意不说「提交后要更新清单」——否则它照做也分不清是听指令还是被提醒纠回
        Disposable d = agent.submit("""
                只在临时目录 %s 里工作（它已 git init）。**不要读写、更不要提交你当前所在的仓库**——
                本任务与那个仓库无关，任何对它的改动都会破坏开发者的工作树。
                请为下面这个六步任务建一份 TodoWrite 清单，然后一口气依次执行完，中途不要停下来问我：
                ① 在临时目录创建 hello.txt 内容为 hello；② 在该目录 git add 并 commit 一次；
                ③ 创建 world.txt 内容为 world；④ 再 add 并 commit 一次；⑤ 运行 ls 确认；
                ⑥ 用 git log --oneline 确认两次提交都在。
                所有命令都必须显式 cd 到该临时目录（或用 git -C），git 身份用 -c 内联。""".formatted(root));

        long deadline = System.currentTimeMillis() + TURN_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline
                && (rec.completed.isEmpty() && rec.errors.isEmpty())) {
            Thread.sleep(200);
        }
        d.dispose();
        assertTrue(rec.errors.isEmpty(), "回合报错：" + rec.errors);

        // ---- 安全护栏：宿主仓库必须原封不动（比「脏不脏」更严：跑前跑后必须逐字一致）----
        String hostHeadAfter = hostHead();
        String hostStatusAfter = runHostGit("status", "--porcelain");
        System.out.println("[spike] 宿主仓库 HEAD 未变 = " + hostHeadBefore.equals(hostHeadAfter)
                + "；工作树状态未变 = " + hostStatusBefore.equals(hostStatusAfter));
        assertTrue(hostHeadBefore.equals(hostHeadAfter),
                "本 spike 污染了宿主仓库（HEAD " + hostHeadBefore + " → " + hostHeadAfter + "），必须回退");
        assertEquals(hostStatusBefore, hostStatusAfter,
                "本 spike 改动了宿主仓库工作树（跑前/跑后 status 不一致）");

        // ---- 机制断言 1：真实模型确实建了清单（走的就是生产装配的 TodoWrite → 快照进提醒器）----
        boolean built = rec.toolOrder.contains("TodoWrite");
        System.out.println("[spike] 真实模型建清单（TodoWrite 出现在工具序列）= " + built);
        assertTrue(built, "本 spike 的前提是模型先建清单；没建则该轮不可用于测量（工具序列=" + rec.toolOrder + "）");

        // ---- 机制断言 2：会话持久化里是否出现注入 ----
        List<Message> messages = rt.sessionService().getMessages(sessionId);
        // 工具返回不在 getText() 里：ToolResponseMessage 的正文在 getResponses()[].responseData()
        StringBuilder all = new StringBuilder();
        for (Message m : messages) {
            all.append(m.getText());
            if (m instanceof org.springframework.ai.chat.messages.ToolResponseMessage trm) {
                for (var r : trm.getResponses()) {
                    all.append(r.responseData());
                }
            }
        }
        String joined = all.toString();
        boolean injected = joined.contains("[任务面板提醒]");
        System.out.println("[spike] 会话消息里出现提醒注入 = " + injected);
        System.out.println("[spike] 会话里能读到工具返回原文（证明落盘含工具结果，注入检测可信）= "
                + joined.contains("bash_id"));
        System.out.println("[spike] 工具调用序列 = " + rec.toolOrder);
        // 逐个 Bash 调用报告「是不是被识别为提交」+ 紧随其后的调用（诊断 arming 路径是否被走到）
        for (int i = 0; i < rec.toolOrder.size(); i++) {
            if (!"Bash".equals(rec.toolOrder.get(i))) {
                continue;
            }
            boolean isCommit = COMMIT.matcher(rec.toolInputs.get(i)).find();
            String next = (i + 1 < rec.toolOrder.size()) ? rec.toolOrder.get(i + 1) : "(回合结束)";
            System.out.println("[spike]   Bash#" + i + " commit=" + isCommit + " → 下一个调用=" + next
                    + "  cmd=" + rec.toolInputs.get(i).replaceAll("\\s+", " ").substring(0,
                        Math.min(90, rec.toolInputs.get(i).length())));
        }

        // ---- 观察值：模型响应（只打印，不断言）----
        int firstTodo = rec.toolOrder.indexOf("TodoWrite");
        int todoAfterFirst = 0;
        for (int i = firstTodo + 1; i >= 0 && i < rec.toolOrder.size(); i++) {
            if ("TodoWrite".equals(rec.toolOrder.get(i))) {
                todoAfterFirst++;
            }
        }
        // ---- 机制断言（条件式）：出现「被识别的提交 + 下一次调用不是 TodoWrite」时，必须有注入 ----
        // 条件式而非无条件：模型在提交后立刻更新清单时不该有提醒（那正是设计意图），此时断言自动空过。
        boolean skipAfterCommit = false;
        for (int i = 0; i + 1 < rec.toolOrder.size(); i++) {
            if ("Bash".equals(rec.toolOrder.get(i)) && COMMIT.matcher(rec.toolInputs.get(i)).find()
                    && !"TodoWrite".equals(rec.toolOrder.get(i + 1))) {
                skipAfterCommit = true;
            }
        }
        System.out.println("[spike] 出现过「提交后跳过更新」= " + skipAfterCommit);
        if (skipAfterCommit) {
            assertTrue(injected, "确认提交后跳过了更新，却没有任何注入——提醒链路断了");
        }

        System.out.println("[spike] 首次建清单之后的 TodoWrite 调用次数 = " + todoAfterFirst
                + "（>0 表示模型在回合内主动更新了清单）");
        System.out.println("[spike] 采样到的会话消息条数 = " + messages.size());
        if (joined.contains("[任务面板提醒]")) {
            int i = joined.indexOf("[任务面板提醒]");
            System.out.println("[spike] 注入片段 = " + joined.substring(i, Math.min(joined.length(), i + 320))
                    .replace("\n", " ⏎ "));
        }
    }
}

package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfig;
import io.github.javaside.springai.codetui.agent.permission.PermissionDecision;
import io.github.javaside.springai.codetui.agent.permission.PermissionEngine;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 springai-code-tui 的「安全现实」，不是证明系统安全，而是把边界的真实情况写死成测试，
 * 防止后续改动误以为整个 root 是沙箱。
 *
 * <p><b>权限管理落地之后，现实分两层，两层都是真的，谁也别拿其中一层去盖另一层</b>：
 * <ul>
 *   <li><b>生产调用路径上有一道护栏</b>：模型发起的工具调用，走的是
 *       {@code AgentTools.build(...)} 装配出来的那批 {@link PermissionCallback}——
 *       执行前先过 {@code PermissionEngine}，危险动作会停下来问人，deny 规则与内置危险检查
 *       任何模式下（含 BYPASS）都生效。这是<b>防模型手滑</b>的护栏。section 3 钉引擎的判定，
 *       section 4 钉「装配出来的工具真的接在引擎上、拒绝时既不执行也不静默」。</li>
 *   <li><b>工具层依然没有任何沙箱</b>：FileSystemTools / ShellTools / GrepTool / GlobTool
 *       在技术上<b>都不</b>受 root 限制。{@code FileSystemTools} 的 {@code allowedDirectory}
 *       是库的 opt-in 特性，本应用<b>刻意不启用</b>（空 allowedDirectories →
 *       {@code validateAllowedAccess} 直接放行），因为其余工具都能越界、单给 FS 设边界形同虚设、
 *       反而制造「有沙箱」的假象。section 1、2 的 {@code KNOWN_UNSAFE_*} 钉的就是这件事。</li>
 * </ul>
 *
 * <p><b>护栏 ≠ 沙箱</b>。三条推论，每条都在 section 4 有对应用例：
 * <ol>
 *   <li>护栏是<b>拦截器</b>，不是围墙：它长在装饰链最外层，只管住「经它转发」的调用。
 *       谁手里攥着一个<b>裸</b> {@link ToolCallback}（或直接 new 一个 {@code FileSystemTools}），
 *       就完全不经过它——{@code KNOWN_UNSAFE_rawToolCallback_bypassesPermissionLayer} 钉住这点。</li>
 *   <li>护栏是<b>检查点</b>，不是禁令：人点了「允许」之后，工具照样能写 root 之外——
 *       {@code KNOWN_UNSAFE_approvedWriteStillEscapesRoot} 钉住这点。</li>
 *   <li>因此它<b>不是</b>防提示注入的安全边界：注入能骗过的是「人在面板上点允许」这一步，
 *       而这一步之后什么都拦不住。真要边界，得靠进程外的东西（容器 / 只读挂载 / 独立用户），
 *       不是靠本进程里的一个装饰器。</li>
 * </ol>
 *
 * <p>一句话给未来的读者：这个类全绿<b>不代表</b>工具被关进了 root。谁要是把「有权限模式了」
 * 理解成「有沙箱了」，section 2 与 section 4 的 {@code KNOWN_UNSAFE_*} 会当场纠正他。
 */
class AgentToolsSecurityTest {

    /** FileSystemTools 拒绝越界时返回的错误串前缀（javap+实测确认：返回字符串，不抛异常）。 */
    private static final String ACCESS_DENIED = "Error: Access denied";

    /** {@link PermissionCallback#denyMessage} 给模型的拒绝串前缀。 */
    private static final String PERMISSION_DENIED = "Permission denied:";

    // ---------------------------------------------------------------------
    // 1. FileSystemTools —— 库能沙箱（allowedDirectory 生效），但本应用默认不沙箱（空 builder）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("库能力：配了 allowedDirectory 时，root 外绝对路径被拒（证明沙箱是真的、只是本应用没开）")
    void library_withAllowedDirectory_rejectsOutside(@TempDir Path root) throws Exception {
        FileSystemTools fs = FileSystemTools.builder().allowedDirectory(root).build();

        Path outside = Files.createTempDirectory("escape_").resolve("escape.txt");
        String result = fs.write(outside.toString(), "should be blocked");

        assertTrue(result.startsWith(ACCESS_DENIED),
                "配了 allowedDirectory 时，写 root 之外必须被拒绝，实际返回: " + result);
        assertFalse(Files.exists(outside), "被拒绝的文件不应真的落盘");
    }

    @Test
    @DisplayName("应用现实：默认 build（空 builder）不设边界——root 外绝对路径可写（无沙箱）")
    void appDefault_absolutePathOutsideRoot_isAllowed(@TempDir Path root) throws Exception {
        // 与 AgentTools.build 一致：不调用 allowedDirectory，空 allowedDirectories 即放行任何路径。
        FileSystemTools fs = FileSystemTools.builder().build();

        Path outsideDir = Files.createTempDirectory("app_no_sandbox_");
        Path outside = outsideDir.resolve("escape.txt");
        String result = fs.write(outside.toString(), "written outside root");

        assertFalse(result.startsWith(ACCESS_DENIED),
                "本应用默认无沙箱：写 root 之外不应被拒绝，实际返回: " + result);
        assertTrue(Files.exists(outside), "无沙箱：文件应真的落到 root 之外");
        assertTrue(Files.readString(outside).contains("written outside root"),
                "无沙箱：root 外文件正文应写入成功");
    }

    @Test
    @DisplayName("应用现实：默认 build 时 root 内读写照常成功")
    void appDefault_insideRoot_succeeds(@TempDir Path root) throws Exception {
        FileSystemTools fs = FileSystemTools.builder().build();

        Path inside = root.resolve("hello.txt");
        String writeResult = fs.write(inside.toString(), "hi");
        assertFalse(writeResult.startsWith(ACCESS_DENIED),
                "root 内写入不应被拒绝，实际返回: " + writeResult);
        assertTrue(Files.exists(inside), "文件应真的被写到磁盘");

        String readResult = fs.read(inside.toString(), null, null);
        assertFalse(readResult.startsWith(ACCESS_DENIED),
                "root 内读取不应被拒绝，实际返回: " + readResult);
        assertTrue(readResult.contains("hi"), "读回的内容应包含写入的正文");
    }

    // ---------------------------------------------------------------------
    // 2. 记录性测试（钉住不安全事实）——Shell/Grep/Glob 同样不受 root 限制
    //    命名/注释刻意大写「不受限」，让任何人一眼看出这是「已知不安全」而非疏漏。
    //    权限层<b>没有</b>改变这一节的任何一条：它拦的是调用，不是工具的能力。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("记录：Shell 不受 root 限制——已知残余风险（可读 root 外文件）")
    void KNOWN_UNSAFE_shell_isNotRootSandboxed(@TempDir Path root) throws Exception {
        // root 之外放一个「机密」文件
        Path outsideDir = Files.createTempDirectory("outside_shell_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // ShellTools 没有 root 参数，也不做任何边界检查：绝对路径 cat 直接读到 root 外内容。
        ShellTools sh = ShellTools.builder().build();
        String out = sh.bash("cat " + outsideDir.resolve("secret.txt"), 5000L, "read outside root", false);

        // 这是「已知不安全」事实——若哪天工具真的加了沙箱，这个断言会失败并提醒我们更新此处的安全现实。
        assertTrue(out.contains("TOPSECRET_MARKER"),
                "已知现实：ShellTools 不受 root 限制，能读到 root 外文件。实际返回: " + out);
    }

    @Test
    @DisplayName("记录：Grep 不受 workingDirectory 限制——已知残余风险（可搜 root 外目录）")
    void KNOWN_UNSAFE_grep_isNotRootSandboxed(@TempDir Path root) throws Exception {
        Path outsideDir = Files.createTempDirectory("outside_grep_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // workingDirectory 只是默认基准；传绝对路径就能越界搜索。
        GrepTool grep = GrepTool.builder().workingDirectory(root).build();
        String out = grep.grep("TOPSECRET_MARKER", outsideDir.toString(),
                null, null, null, null, null, null, null, null, null, null, null);

        assertTrue(out.contains("secret.txt"),
                "已知现实：GrepTool 的 workingDirectory 非强制边界，能搜 root 外目录。实际返回: " + out);
    }

    @Test
    @DisplayName("记录：Glob 不受 workingDirectory 限制——已知残余风险（可列 root 外目录）")
    void KNOWN_UNSAFE_glob_isNotRootSandboxed(@TempDir Path root) throws Exception {
        Path outsideDir = Files.createTempDirectory("outside_glob_");
        Files.writeString(outsideDir.resolve("secret.txt"), "TOPSECRET_MARKER");

        // 同理，传绝对 path 参数即可越界列举。
        GlobTool glob = GlobTool.builder().workingDirectory(root).build();
        String out = glob.glob("*.txt", outsideDir.toString());

        assertTrue(out.contains("secret.txt"),
                "已知现实：GlobTool 的 workingDirectory 非强制边界，能列 root 外目录。实际返回: " + out);
    }

    // ---------------------------------------------------------------------
    // 3. 引擎层的判定——护栏「想拦什么」
    //    这一节只问引擎要结论，不碰工具；section 4 才验证结论真被执行。
    //    引擎全部离线自建（空配置），刻意不读两层 permissions.json：读了测试结论就会随
    //    开发者本机 ~/.codetui/permissions.json 漂移。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("护栏：默认模式下，写工作区文件会被拦下要求授权（不是直接放行）")
    void engine_defaultMode_asksBeforeWrite(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(root,
                PermissionConfig.empty(), PermissionMode.DEFAULT, false);

        PermissionDecision d = engine.decide("Write",
                "{\"filePath\":\"" + json(root.resolve("a.txt")) + "\"}");

        assertEquals(PermissionBehavior.ASK, d.behavior(),
                "默认模式下写文件必须先问，实际：" + d);
    }

    @Test
    @DisplayName("护栏：BYPASS 模式下 deny 规则仍然生效，但内置危险检查被跳过（行为变更）")
    void engine_bypass_stillHonoursDenyButSkipsBuiltins(@TempDir Path root) {
        PermissionEngine engine = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.BYPASS, List.of(
                        PermissionRule.parse("Bash(git push:*)",
                                PermissionBehavior.DENY, RuleScope.USER))),
                PermissionMode.BYPASS, true);

        assertEquals(PermissionBehavior.DENY,
                engine.decide("Bash", "{\"command\":\"git push origin main\"}").behavior(),
                "BYPASS 也盖不住 deny 规则");
        // 行为变更：内置危险检查在 BYPASS 下不再拦。一个名字带 dangerously 的开关，
        // 打开之后还在弹窗就是在骗人——而弹窗在半无人值守时就是死锁（应答队列无超时）。
        // deny 规则保留是因为它来源不同：用户写在 permissions.json 里的明令，不是本项目的意见。
        assertEquals(PermissionBehavior.ALLOW,
                engine.decide("Bash", "{\"command\":\"rm -rf /\"}").behavior(),
                "BYPASS 就该跳过内置危险检查（deny 规则另算）");
    }

    // ---------------------------------------------------------------------
    // 4. 生产调用路径——护栏「真拦住了什么」，以及「拦不住什么」
    //    工具一律从 AgentTools.build(...) 的装配产物里取，不在测试里另拼一套：
    //    另拼的话，哪天装配点漏包 PermissionCallback，本节照样全绿（自证自洽）。
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("护栏生效：装配出来的 Write 写 root 之外，会真的发出审批请求；被拒时不执行、也不静默")
    void assembledWrite_outsideRoot_isInterceptedAndRefused(@TempDir Path root) throws Exception {
        // 显式提供应答方，而<b>不</b>依赖 AgentListener.onPermissionRequested 的默认 DENY——
        // 靠默认值的话，「引擎压根没被问到」与「问了被拒」在返回值上无法区分，用例就只是在
        // 证明「没接 UI 时调用会失败」，护栏是否接上完全测不到。
        RecordingApprover approver = new RecordingApprover(PermissionOutcome.DENY);
        ToolCallback write = assembledTool(root, approver, "Write");

        Path outsideDir = Files.createTempDirectory("guarded_write_");
        Path outside = outsideDir.resolve("blocked.txt");
        String result = write.call("{\"filePath\":\"" + json(outside) + "\",\"content\":\"x\"}");

        assertEquals(1, approver.requests.size(),
                "写 root 之外没有触发任何审批请求——权限层没接在这条调用路径上。实际返回: " + result);
        PermissionRequest req = approver.requests.get(0);
        assertEquals("Write", req.toolName(), "审批请求认错了工具");
        assertEquals(outside.toString(), req.target(),
                "审批面板上会显示错的目标文件，用户据以决策的信息是错的");
        assertTrue(result.startsWith(PERMISSION_DENIED),
                "被拒时必须把拒绝原因回给模型（而不是静默成功或抛异常炸掉回合），实际返回: " + result);
        assertFalse(Files.exists(outside), "被拒的写入不该真的落盘");
    }

    @Test
    @DisplayName("现实：护栏是检查点不是禁令——人点了允许，装配出来的工具照样写到 root 之外")
    void KNOWN_UNSAFE_approvedWriteStillEscapesRoot(@TempDir Path root) throws Exception {
        RecordingApprover approver = new RecordingApprover(PermissionOutcome.ALLOW_ONCE);
        ToolCallback write = assembledTool(root, approver, "Write");

        Path outsideDir = Files.createTempDirectory("approved_escape_");
        Path outside = outsideDir.resolve("escape.txt");
        String result = write.call("{\"filePath\":\"" + json(outside) + "\",\"content\":\"written after approval\"}");

        assertEquals(1, approver.requests.size(), "获批前应当问过一次，实际返回: " + result);
        // 已知现实：权限层放行之后，工具层没有任何目录约束兜底。
        assertTrue(Files.exists(outside),
                "已知现实：获批后工具仍能写 root 之外——护栏不是沙箱。实际返回: " + result);
        assertEquals("written after approval", Files.readString(outside).strip(),
                "已知现实：root 外文件正文照常写入");
    }

    @Test
    @DisplayName("现实：护栏只管住经它转发的调用——裸 ToolCallback / 裸工具完全不经过它")
    void KNOWN_UNSAFE_rawToolCallback_bypassesPermissionLayer(@TempDir Path root) throws Exception {
        // 引擎说「这次写 root 之外要先问」……
        PermissionEngine engine = new PermissionEngine(root,
                PermissionConfig.empty(), PermissionMode.DEFAULT, false);
        Path outsideDir = Files.createTempDirectory("raw_bypass_");
        Path outside = outsideDir.resolve("escape.txt");
        assertEquals(PermissionBehavior.ASK,
                engine.decide("Write", "{\"filePath\":\"" + json(outside) + "\"}").behavior(),
                "前提不成立：这条路径本该要求审批");

        // ……但引擎只是个被 PermissionCallback 调用的判定器，不是执行链上的关卡。
        // 拿到裸工具的人（本方法、以及任何未经装配点包装的新装配代码）根本不会去问它。
        FileSystemTools raw = FileSystemTools.builder().build();
        String result = raw.write(outside.toString(), "no callback, no guardrail");

        assertFalse(result.startsWith(ACCESS_DENIED), "裸工具无沙箱，不该被拒，实际返回: " + result);
        assertTrue(Files.exists(outside),
                "已知现实：绕开 PermissionCallback 就没有任何拦截——新增装配点若漏包，等于全裸");
    }

    // ── helper ──────────────────────────────────────────────────────────

    /** 只记录并按预设结果作答的应答方；作答是<b>同步</b>的，故工具线程不会 park，用例无需另起线程。 */
    private static final class RecordingApprover extends StubListener {
        private final PermissionOutcome outcome;
        final List<PermissionRequest> requests = new CopyOnWriteArrayList<>();

        RecordingApprover(PermissionOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public void onPermissionRequested(long turnId, PermissionRequest request) {
            requests.add(request);
            request.responder().respond(outcome);
        }
    }

    /**
     * 从 {@code AgentTools.build} 的装配产物里按注册名取一个工具（同 {@code RuntimeToolSet} 的取法）。
     * 全程离线：假 key 的 DeepSeek provider + {@code AgentTools.testEngine}（空配置、DEFAULT、禁 BYPASS）。
     */
    private static ToolCallback assembledTool(Path root, AgentListener listener, String name) {
        ChatClient client = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))),
                root, listener, null, AgentTools.testEngine(root)).client();
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        assertInstanceOf(DefaultChatClient.DefaultChatClientRequestSpec.class, spec,
                "取不到装配期工具列表：ChatClient 实现已不是 DefaultChatClient");
        List<ToolCallback> tools =
                ((DefaultChatClient.DefaultChatClientRequestSpec) spec).getToolCallbacks();
        for (ToolCallback t : tools) {
            if (name.equals(t.getToolDefinition().name())) {
                return t;
            }
        }
        throw new AssertionError("装配产物里没有工具 " + name + "，实际："
                + tools.stream().map(t -> t.getToolDefinition().name()).toList());
    }

    /** 路径进 JSON 字符串（Windows 反斜杠会被当转义符）。 */
    private static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }
}

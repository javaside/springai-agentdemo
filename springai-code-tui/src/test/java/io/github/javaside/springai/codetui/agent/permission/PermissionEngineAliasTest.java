package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 匹配的放宽<b>只在 deny/ask 方向</b>。
 *
 * <p><b>这一类的安全支点是那两条「allow 仍不命中」的反向断言</b>：所有人都会记得测
 * 「deny 现在能拦住 /ETC 了」，没人会想起测「allow 仍然拦不住」——而后者才是不可逆的方向。
 *
 * <p><b>刻意不创建真实的 /ETC 目录</b>：APFS 上它与 /etc 是同一个文件，那样的测试会因
 * <b>错误的原因</b>变绿，换到 Linux CI 就红。这里只打匹配逻辑，不碰宿主文件系统。
 *
 * <h2>为什么两条 allow 断言不用 {@code /etc}</h2>
 * 用 {@code /etc} 会得到一个<b>永远不会失败</b>的测试：{@code Write /ETC/passwd} 在
 * 判定第 2 步就被 {@link DangerousPaths}「写入项目与家目录之外的系统位置」拦成 ASK，
 * 第 5 步的 allow 规则<b>根本轮不到</b>——于是无论 allow 折不折叠，断言都是 ASK。
 * 本项目已有五个「不会失败的测试」绿着上线的记录，故这里改用<b>工作区内</b>的路径：
 * 内置检查放行、模式默认给 ASK，allow 一旦命中结论立刻变 ALLOW，断言才真有鉴别力
 * （已用「让 ALLOW 也造折叠孪生 / 也认别名」两个变异实测确认会变红）。
 *
 * <h2>{@code @TempDir} 自己就带一层符号链接</h2>
 * macOS 上它位于 {@code /var/folders/…}，而 {@code /var} 是指向 {@code /private/var} 的链接。
 * 不先 {@code toRealPath()} 就往下建，「有没有链」这件事就由宿主决定而不是由用例决定：
 * 写下的规则前缀是 {@code /var/…}、别名解析出来的却是 {@code /private/var/…}，
 * 符号链接那两条会因为<b>无关的原因</b>红。故一律先取 real path 再建目录。
 */
class PermissionEngineAliasTest {

    private static PermissionEngine engine(Path root, PermissionRule... rules) {
        return engine(root, PermissionMode.DEFAULT, rules);
    }

    private static PermissionEngine engine(Path root, PermissionMode mode, PermissionRule... rules) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of(rules)),
                mode, false);
    }

    /** @TempDir 自带一层符号链接，先解掉——见类注释。 */
    private static Path realRoot(Path tempDir) throws IOException {
        return tempDir.toRealPath();
    }

    private static String writeInput(Path p) {
        return "{\"filePath\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    @DisplayName("deny 折叠大小写：/etc/** 拦得住 /ETC/passwd")
    void denyFoldsCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior());
    }

    @Test
    @DisplayName("deny 折叠大小写：规则写大写、目标写小写同样命中")
    void denyFoldsCaseInRule(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(/ETC/**)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(Path.of("/etc/passwd"))).behavior());
    }

    @Test
    @DisplayName("★ 安全支点：allow 不得折叠——<root>/sub/** 放行不了 <root>/SUB/x.txt")
    void allowDoesNotFoldCase(@TempDir Path tempDir) throws IOException {
        Path root = realRoot(tempDir);
        PermissionEngine e = engine(root, PermissionRule.parse(
                "Write(" + root + "/sub/**)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(root.resolve("SUB/x.txt"))).behavior(),
                "allow 一旦折叠，一条规则就比写下时以为的覆盖面更宽——而放行是不可逆的");
    }

    @Test
    @DisplayName("ask 规则同样折叠（它与 deny 同属「问一下无害」的方向）")
    void askFoldsCase(@TempDir Path tempDir) throws IOException {
        // ACCEPT_EDITS：工作区内的写本会被模式默认直接放行，ask 规则命中才会退回询问，
        // 断言因此能区分「折叠了」与「没折叠」；DEFAULT 下两种结论都是 ASK，测不出东西。
        Path root = realRoot(tempDir);
        PermissionEngine e = engine(root, PermissionMode.ACCEPT_EDITS, PermissionRule.parse(
                "Write(" + root + "/sub/**)", PermissionBehavior.ASK, RuleScope.USER));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(root.resolve("SUB/x.txt"))).behavior());
    }

    @Test
    @DisplayName("命令目标也折叠：macOS 上 RM -rf 真能执行（/bin/RM 解析到 /bin/rm）")
    void denyFoldsCommandCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Bash(rm -rf /tmp/x:*)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Bash", "{\"command\":\"RM -rf /tmp/x\"}").behavior());
    }

    @Test
    @DisplayName("命令折叠也认分段：多段命令里的 RM 段命中 deny")
    void denyFoldsCommandCaseInSegment(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Bash(rm -rf /tmp/x:*)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Bash", "{\"command\":\"echo hi && RM -rf /tmp/x\"}").behavior());
    }

    @Test
    @DisplayName("裸工具名规则（pattern == null）不生成折叠孪生，且不得 NPE")
    void wholeToolRuleDoesNotNpe(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("WebFetch(*)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("WebFetch", "{\"url\":\"https://x/y\"}").behavior());
    }

    @Test
    @DisplayName("deny 认符号链接解析后的写法：经 link 指向被禁目录也拦得住")
    void denyFollowsSymlink(@TempDir Path tempDir) throws Exception {
        Path root = realRoot(tempDir);
        Path secret = Files.createDirectories(root.resolve("secret"));
        Path link;
        try {
            link = Files.createSymbolicLink(root.resolve("link"), secret);
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        PermissionEngine e = engine(root, PermissionRule.parse(
                "Write(" + secret + "/**)", PermissionBehavior.DENY, RuleScope.USER));

        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(link.resolve("new.txt"))).behavior(),
                "目标文件尚不存在、父目录经链接指向被禁目录——这是两步绕过");
    }

    @Test
    @DisplayName("★ 安全支点：allow 不认符号链接解析后的写法")
    void allowDoesNotFollowSymlink(@TempDir Path tempDir) throws Exception {
        Path root = realRoot(tempDir);
        Path real = Files.createDirectories(root.resolve("real"));
        try {
            Files.createSymbolicLink(root.resolve("link"), real);
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        PermissionEngine e = engine(root, PermissionRule.parse(
                "Write(" + real + "/**)", PermissionBehavior.ALLOW, RuleScope.USER));

        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(root.resolve("link/new.txt"))).behavior(),
                "allow 认了别名，就会放行一条你没有明确写下的路径");
    }

    @Test
    @DisplayName("运行期加的会话规则也要有折叠孪生（否则「本会话不再问」的 deny 形态漏折）")
    void sessionRulesAlsoFold(@TempDir Path root) {
        PermissionEngine e = engine(root);
        e.addSessionRule(PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.SESSION));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior());
    }

    // ── 相对 pattern 的折叠 ──────────────────────────────────────────────
    //
    // 绝对 pattern 折得了、相对 pattern 折不了，这个边界对用户是<b>任意的</b>——
    // 没人能预料，而 Write(src/**) 恰恰是最自然的写法。根因在 globMatches：
    // 折叠孪生匹配时目标已折成小写，若 root 仍传原大小写，t.startsWith(root) 不成立，
    // 相对化那条分支根本走不到（整条相对 pattern 的路就此断掉）。

    @Test
    @DisplayName("相对 pattern 也要折叠：deny Read(src/**) 拦得住 <root>/SRC/x.txt")
    void denyFoldsRelativePattern(@TempDir Path tempDir) throws IOException {
        Path root = realRoot(tempDir);
        PermissionEngine e = engine(root,
                PermissionRule.parse("Read(src/**)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Read", writeInput(root.resolve("SRC/x.txt"))).behavior(),
                "相对 pattern 折不了的话，deny Read(src/**) 加个大写 SRC 就绕开了");
    }

    /**
     * 反向断言：allow 方向<b>仍然</b>不折叠。
     *
     * <p><b>这里刻意用 Write 而不是 Read</b>——虽然上一条 deny 用的是 Read。
     * Read 是 READ_ONLY，模式默认本来就是 ALLOW，于是「allow 命中」与「allow 不命中」
     * 两种情况的结论都是 ALLOW，断言<b>不可能失败</b>（正是本类注释警告的那种测试）。
     * Write 在 DEFAULT 下模式默认是 ASK，allow 一旦命中立刻变 ALLOW，断言才有鉴别力。
     */
    @Test
    @DisplayName("★ 安全支点：相对 pattern 的 allow 仍不折叠——allow Write(src/**) 放行不了 <root>/SRC/x.txt")
    void allowDoesNotFoldRelativePattern(@TempDir Path tempDir) throws IOException {
        Path root = realRoot(tempDir);
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(src/**)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(root.resolve("SRC/x.txt"))).behavior(),
                "放宽只在 deny/ask 方向；allow 折叠会让一条规则比写下时以为的覆盖面更宽");
    }

    /**
     * 上一条的<b>对照组</b>：证明那个 ASK 是「大小写没折」造成的，
     * 而不是「相对 pattern 的 allow 整个就不工作」。少了这条，上一条会因错误的原因变绿。
     */
    @Test
    @DisplayName("对照：原大小写下相对 pattern 的 allow 照常命中")
    void allowRelativePatternStillWorksInExactCase(@TempDir Path tempDir) throws IOException {
        Path root = realRoot(tempDir);
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(src/**)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Write", writeInput(root.resolve("src/x.txt"))).behavior());
    }
}

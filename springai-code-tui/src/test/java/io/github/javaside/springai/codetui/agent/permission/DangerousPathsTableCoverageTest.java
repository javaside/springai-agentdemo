package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常量表的<b>逐条</b>覆盖测试——由表本身驱动断言，而不是每张表挑一个样本。
 *
 * <p><b>为什么需要它</b>：代码审查做过一次变异实测——把九张表缩到只剩别的测试用到的那几条，
 * 整个套件仍然 <b>35/35 全绿</b>。被静默删掉且无人报警的包括
 * {@code curl} {@code wget} {@code scp} {@code base64} {@code openssl}（整个网络外传半边）、
 * {@code install}、{@code rmdir} {@code shred} {@code srm}、
 * {@code xargs} {@code eval} {@code exec} {@code nohup} {@code doas}、
 * {@code .netrc} {@code .pgpass} {@code .pypirc}、{@code .pfx} {@code .keystore} {@code .ppk}、
 * {@code .kube/config} 等约四成条目。
 *
 * <p>对一个「价值全在这张表是完整的」的组件，这是不可接受的：
 * <b>它正是让那些洞得以全绿上线的原因</b>。不修它，这次补进去的条目下次照样会被悄悄删掉。
 *
 * <p>故本测试用<b>反射</b>读常量表，逐条构造一个该条目应当命中的输入。
 * 反射而非把字段改成包私有，是为了让<b>将来新增的表也自动被覆盖</b>——
 * 漏加断言的代价是这里报「未覆盖的表」，而不是无声无息。
 */
class DangerousPathsTableCoverageTest {

    private static final Path ROOT = Path.of("/work/proj");
    private static final String HOME = System.getProperty("user.home");

    /** 反射取出私有静态常量表。 */
    @SuppressWarnings("unchecked")
    private static Set<String> table(String name) throws Exception {
        Field f = DangerousPaths.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    /**
     * 断言表里<b>至少</b>包含这些条目——这才是真正抓「被删」的那道锁。
     *
     * <p><b>为什么单靠遍历表不够（我第一版就是这么写的，实测抓不住）</b>：
     * 遍历表逐条验证，证明的是「表里现有的每一条都有效」，而不是「表是完整的」。
     * 把 {@code DELETE_COMMANDS} 从四条砍到一条，循环只是少跑三轮，剩下那条照样通过——
     * 全绿。要抓住删除，期望值必须<b>独立于表本身</b>，也就是写死在这里。
     */
    private static void mustContain(String tableName, String... required) throws Exception {
        Set<String> actual = table(tableName);
        List<String> missing = new ArrayList<>();
        for (String r : required) {
            if (!actual.contains(r)) {
                missing.add(r);
            }
        }
        assertTrue(missing.isEmpty(),
                tableName + " 少了这些条目（删条目不会让逐条遍历变红，只有这条断言能抓）：" + missing);
    }

    @Test
    @DisplayName("表不得被悄悄删条目——期望值写死在测试里，独立于表本身")
    void tablesKeepTheirRequiredEntries() throws Exception {
        mustContain("DELETE_COMMANDS", "rm", "rmdir", "shred", "srm");
        mustContain("WRITER_COMMANDS", "tee", "dd", "install");
        mustContain("BULK_COPY_COMMANDS", "cp", "mv", "curl", "wget", "scp", "rsync",
                "base64", "openssl", "tar", "zip");
        mustContain("COMMAND_WRAPPERS", "sudo", "doas", "env", "xargs", "eval", "exec",
                "command", "builtin", "nohup", "nice", "ionice", "stdbuf", "setsid", "timeout", "time");
        mustContain("SECRET_FILES", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
                "credentials", "secring.gpg", ".netrc", ".pgpass", ".pypirc", ".git-credentials");
        mustContain("SECRET_EXTENSIONS", ".key", ".pem", ".p12", ".pfx", ".jks",
                ".keystore", ".ppk", ".asc");
        mustContain("SHELL_CONFIG_FILES", ".gitconfig", ".gitmodules", ".zshrc", ".bashrc",
                ".zshenv", ".zprofile", ".bash_profile", ".profile", ".npmrc", ".envrc");
        mustContain("SENSITIVE_NESTED", ".m2/settings.xml", ".gradle/gradle.properties",
                ".kube/config", "gh/hosts.yml", "gh/config.yml", "git/config");
        mustContain("AUTO_EXEC_NESTED", ".vscode/settings.json", ".vscode/tasks.json",
                ".vscode/launch.json", ".idea/workspace.xml", "fish/config.fish", "fish/conf.d");
        mustContain("AUTO_EXEC_DIR_SEGMENTS", "launchagents", "launchdaemons", ".githooks");
        mustContain("SECRET_STORE_DIRS", ".gnupg", ".aws", "keychains", ".docker");
        mustContain("SYSTEM_SECRET_PATHS", "/etc/shadow", "/etc/sudoers", "/etc/master.passwd");
        mustContain("SHELL_INTERPRETERS", "bash", "sh", "zsh");
        mustContain("READABLE_SECRET_CONFIGS", ".npmrc", ".gitconfig");
        mustContain("WRITABLE_ROOTS", "/tmp", "/private/tmp", "/var/folders");
    }

    /** 逐条断言：表里每一项都要让 {@code probe} 返回非 null，否则这一条是死条目。 */
    private static void eachEntryBites(String tableName, Function<String, String> probe)
            throws Exception {
        List<String> dead = new ArrayList<>();
        for (String entry : table(tableName)) {
            if (probe.apply(entry) == null) {
                dead.add(entry);
            }
        }
        assertTrue(dead.isEmpty(),
                tableName + " 里这些条目删掉也没人报警（等于没在保护它们）：" + dead);
    }

    @Test
    @DisplayName("删除 / 写入 / 拷贝 / 包装词四张命令表逐条生效")
    void everyCommandEntryBites() throws Exception {
        eachEntryBites("DELETE_COMMANDS",
                c -> DangerousPaths.checkCommand(c + " -rf /", ROOT));
        eachEntryBites("WRITER_COMMANDS",
                c -> DangerousPaths.checkCommand(c + " " + HOME + "/.zshrc", ROOT));
        eachEntryBites("BULK_COPY_COMMANDS",
                c -> DangerousPaths.checkCommand(c + " evil /usr/local/bin/git", ROOT));
        // 包装词的作用是「跳过它、把 head 落到后面那条真命令上」，故拿 rm -rf / 作载荷
        eachEntryBites("COMMAND_WRAPPERS",
                w -> DangerousPaths.checkCommand(w + " rm -rf /", ROOT));
    }

    @Test
    @DisplayName("密钥文件名 / 扩展名两张表逐条生效（读方向）")
    void everySecretEntryBites() throws Exception {
        eachEntryBites("SECRET_FILES",
                n -> DangerousPaths.checkRead(Path.of(HOME, n), ROOT));
        eachEntryBites("SECRET_EXTENSIONS",
                ext -> DangerousPaths.checkRead(Path.of(HOME, "x" + ext), ROOT));
        eachEntryBites("SYSTEM_SECRET_PATHS",
                p -> DangerousPaths.checkRead(Path.of(p), ROOT));
    }

    @Test
    @DisplayName("配置文件三张表逐条生效（写方向）")
    void everyConfigEntryBites() throws Exception {
        eachEntryBites("SHELL_CONFIG_FILES",
                n -> DangerousPaths.checkWrite(Path.of(HOME, n), ROOT));
        eachEntryBites("SENSITIVE_NESTED",
                nested -> DangerousPaths.checkWrite(Path.of(HOME, nested), ROOT));
        eachEntryBites("AUTO_EXEC_NESTED",
                nested -> DangerousPaths.checkWrite(Path.of(HOME, nested), ROOT));
        eachEntryBites("AUTO_EXEC_DIR_SEGMENTS",
                seg -> DangerousPaths.checkWrite(Path.of(HOME, seg, "x.plist"), ROOT));
        eachEntryBites("SECRET_STORE_DIRS",
                dir -> DangerousPaths.checkRead(Path.of(HOME, dir, "anything"), ROOT));
    }

    /**
     * 新增了常量表却忘了在上面加断言时，这里报出来。
     *
     * <p>没有这一条，本测试自己就会随着表的增长而慢慢失去覆盖——
     * 而那正是它要防的失效模式。
     */
    @Test
    @DisplayName("新增常量表必须同时补断言，否则这里报未覆盖")
    void noTableEscapesCoverage() {
        Set<String> covered = Set.of(
                "DELETE_COMMANDS", "WRITER_COMMANDS", "BULK_COPY_COMMANDS", "COMMAND_WRAPPERS",
                "SECRET_FILES", "SECRET_EXTENSIONS", "SYSTEM_SECRET_PATHS",
                "SHELL_CONFIG_FILES", "SENSITIVE_NESTED", "AUTO_EXEC_NESTED",
                "AUTO_EXEC_DIR_SEGMENTS", "SECRET_STORE_DIRS",
                // 以下是「豁免 / 白名单」性质的表：条目命中意味着<b>放行</b>，
                // 逐条断言「必须非 null」对它们没有意义，故显式记在此处而非遗漏。
                "SSH_PUBLIC_FILES", "ENV_TEMPLATES", "HARMLESS_DEVICES",
                "WRITABLE_ROOTS", "CODETUI_WORKSPACE", "SHELL_INTERPRETERS",
                "SENSITIVE_DIR_SEGMENTS", "READABLE_SECRET_CONFIGS");

        List<String> uncovered = new ArrayList<>();
        for (Field f : DangerousPaths.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Set.class.isAssignableFrom(f.getType())
                    && !covered.contains(f.getName())) {
                uncovered.add(f.getName());
            }
        }
        assertTrue(uncovered.isEmpty(),
                "新增了常量表但没在本类补逐条断言（或没记进豁免名单）：" + uncovered);
    }

    @Test
    @DisplayName("豁免表反过来钉：条目必须真的豁免，否则是无谓的审批")
    void exemptionTablesReallyExempt() throws Exception {
        List<String> notExempt = new ArrayList<>();
        for (String n : table("SSH_PUBLIC_FILES")) {
            if (DangerousPaths.checkRead(Path.of(HOME, ".ssh", n), ROOT) != null) {
                notExempt.add(n);
            }
        }
        assertTrue(notExempt.isEmpty(), "SSH_PUBLIC_FILES 里这些并没有真的豁免：" + notExempt);

        List<String> tmplNotExempt = new ArrayList<>();
        for (String n : table("ENV_TEMPLATES")) {
            if (DangerousPaths.checkWrite(ROOT.resolve(n), ROOT) != null) {
                tmplNotExempt.add(n);
            }
        }
        assertTrue(tmplNotExempt.isEmpty(), "ENV_TEMPLATES 里这些并没有真的豁免：" + tmplNotExempt);

        // 反向对照：豁免表不该宽到把真密钥也放过
        assertFalse(table("SSH_PUBLIC_FILES").contains("id_rsa"));
        assertFalse(table("ENV_TEMPLATES").contains(".env"));
    }
}

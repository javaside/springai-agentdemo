package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashCommandSplitterTest {

    @Test
    @DisplayName("按 && || ; | 拆段，逐段独立判定")
    void splitsOnAllSeparators() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("ls && pwd || echo hi ; cat a | wc -l");
        assertTrue(s.parseable());
        assertEquals(List.of("ls", "pwd", "echo hi", "cat a", "wc -l"), s.segments());
    }

    @Test
    @DisplayName("只读白名单：全段命中即整条只读")
    void readOnlyWhitelist() {
        assertTrue(BashCommandSplitter.isReadOnly("ls -la"));
        assertTrue(BashCommandSplitter.isReadOnly("git status"));
        assertTrue(BashCommandSplitter.isReadOnly("git diff HEAD~1"));
        assertTrue(BashCommandSplitter.isReadOnly("java -version"));

        assertFalse(BashCommandSplitter.isReadOnly("rm -rf build"));
        assertFalse(BashCommandSplitter.isReadOnly("git push origin main"), "git 子命令白名单不含 push");
        assertFalse(BashCommandSplitter.isReadOnly("mvn test"), "mvn test 会跑代码，不是只读");
        assertFalse(BashCommandSplitter.isReadOnly(""));
    }

    @Test
    @DisplayName("ACCEPT_EDITS 的文件系统写白名单：mkdir/touch/mv/cp，不含 rm")
    void fileSystemWriteWhitelist() {
        assertTrue(BashCommandSplitter.isFileSystemWrite("mkdir -p target/x"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("touch a.txt"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("mv a b"));
        assertTrue(BashCommandSplitter.isFileSystemWrite("cp a b"));
        assertFalse(BashCommandSplitter.isFileSystemWrite("rm a"), "删除永远不进自动放行白名单");
    }

    @Test
    @DisplayName("拆不动就问：命令替换 / 反引号 / 进程替换一律 parseable=false")
    void unparseableConstructs() {
        assertFalse(BashCommandSplitter.split("echo $(whoami)").parseable());
        assertFalse(BashCommandSplitter.split("echo `whoami`").parseable());
        assertFalse(BashCommandSplitter.split("diff <(ls a) <(ls b)").parseable());
        assertFalse(BashCommandSplitter.split("cat > >(tee log)").parseable());
    }

    @Test
    @DisplayName("拆不动就问：引号内含分隔符 → parseable=false（宁烦不漏）")
    void separatorInsideQuotesIsUnparseable() {
        assertFalse(BashCommandSplitter.split("echo 'a && b'").parseable());
        assertFalse(BashCommandSplitter.split("echo \"x ; y\"").parseable());
    }

    @Test
    @DisplayName("拆不动就问：引号不配对 → parseable=false")
    void unbalancedQuoteIsUnparseable() {
        assertFalse(BashCommandSplitter.split("echo 'unterminated").parseable());
    }

    @Test
    @DisplayName("普通引号（内无分隔符）不影响拆分")
    void plainQuotesAreFine() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("grep 'hello world' a.txt && ls");
        assertTrue(s.parseable());
        assertEquals(List.of("grep 'hello world' a.txt", "ls"), s.segments());
    }

    @Test
    @DisplayName("空 / null 命令：可解析但零段（调用方按无目标处理）")
    void emptyCommand() {
        assertTrue(BashCommandSplitter.split("").segments().isEmpty());
        assertTrue(BashCommandSplitter.split(null).segments().isEmpty());
    }

    // ---- 以下为计划外补充：实测复核出的放行漏洞回归钉子 ----

    @Test
    @DisplayName("换行 / 回车是命令分隔符，须拆开逐段判定（实测 bash：echo hi\\n<cmd> 会执行 <cmd>）")
    void newlineIsACommandSeparator() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("echo hi\nrm -rf /");
        assertEquals(List.of("echo hi", "rm -rf /"), s.segments(),
                "若当成单段，首词 echo 命中只读白名单，第二条命令就被静默放行了");
        assertFalse(BashCommandSplitter.isReadOnly("rm -rf /"), "拆出的第二段自然落到 ASK");

        assertEquals(List.of("echo hi", "rm -rf /"),
                BashCommandSplitter.split("echo hi\rrm -rf /").segments());

        assertFalse(BashCommandSplitter.isReadOnly("echo hi\nrm -rf /"),
                "isReadOnly 自身也须挡住多段串，不能只靠调用方先拆");
    }

    @Test
    @DisplayName("反斜杠转义：本类不解释它，但两个方向都只会更保守（多拆一段 / 直接不可解析）")
    void backslashEscapesFailSafe() {
        // bash 实测 `echo a\; echo b` 是一条命令（\; 为字面分号）；这里多拆一段，
        // 每段都要独立过关，比合成一段更严，不会放宽。
        assertEquals(List.of("echo a\\", "echo b"),
                BashCommandSplitter.split("echo a\\; echo b").segments());

        // bash 实测 `echo \' ; echo X` 会执行第二条；本类把 \' 当成引号起始，
        // 于是在「引号内」撞见 ; → 不可解析 → ASK。同样不会漏。
        assertFalse(BashCommandSplitter.split("echo \\' ; echo X").parseable());

        // 双引号内 \" 对 bash 是字面量、对本类是闭合引号。不论落到哪条 fail-safe 路径，
        // 都不许出现「可解析、且危险命令被并进某段里当参数」的结果。
        BashCommandSplitter.Split s = BashCommandSplitter.split("echo \"a\\\" ; rm -rf /\"");
        assertTrue(!s.parseable() || s.segments().contains("rm -rf /"),
                "要么拒绝解析，要么把危险段单独拆出；两者都会落到 ASK");
    }

    @Test
    @DisplayName("here-doc / here-string 落到 ASK（首词带重定向即非只读）")
    void heredocFallsThroughToAsk() {
        BashCommandSplitter.Split s = BashCommandSplitter.split("cat <<EOF\nwhoami\nEOF");
        assertTrue(s.parseable());
        assertFalse(BashCommandSplitter.isReadOnly(s.segments().get(0)),
                "cat <<EOF 含重定向算子，不能按只读 cat 放行");
        assertFalse(BashCommandSplitter.isReadOnly("cat <<< 'x'"));
    }

    @Test
    @DisplayName("与 PermissionRule.hasShellSeparator 一致：复合命令绝不落成含分隔符的单段")
    void agreesWithPermissionRuleOnCompoundCommands() {
        List<String> compound = List.of(
                "ls && pwd", "ls; rm -rf /", "cat a | wc -l", "ls &",
                "echo `whoami`", "echo $(whoami)", "diff <(ls a) <(ls b)",
                "cat > >(tee log)", "echo hi\nrm -rf /", "echo hi\rrm -rf /");
        for (String cmd : compound) {
            assertTrue(PermissionRule.hasShellSeparator(cmd), "前提：这些都算复合命令 → " + cmd);
            BashCommandSplitter.Split s = BashCommandSplitter.split(cmd);
            for (String seg : s.segments()) {
                assertFalse(PermissionRule.hasShellSeparator(seg),
                        "拆出的段仍含分隔符，两处对复合命令的认定不一致：" + cmd + " → " + seg);
            }
        }
    }

    @Test
    @DisplayName("带重定向的段不算只读（实测 bash：echo x > f 会落盘）")
    void redirectionIsNotReadOnly() {
        assertFalse(BashCommandSplitter.isReadOnly("echo pwned > /Users/x/.zshrc"));
        assertFalse(BashCommandSplitter.isReadOnly("cat secret >> out.txt"));
        assertFalse(BashCommandSplitter.isReadOnly("cat < in.txt"), "输入重定向同样超出首词能判定的范围");
        assertFalse(BashCommandSplitter.isFileSystemWrite("touch a > /Users/x/.zshrc"));

        assertTrue(BashCommandSplitter.isReadOnly("grep 'a>b' f.txt"), "引号内的 > 不是重定向");
    }

    @Test
    @DisplayName("git：只读只认真正只读的子命令，且不得带 --output")
    void gitReadOnlyIsNarrow() {
        assertTrue(BashCommandSplitter.isReadOnly("git log --oneline -n 5"));
        assertTrue(BashCommandSplitter.isReadOnly("git show HEAD"));

        assertFalse(BashCommandSplitter.isReadOnly("git branch -D tmp"), "实测会删分支");
        assertFalse(BashCommandSplitter.isReadOnly("git remote add evil https://x"), "实测会改配置");
        assertFalse(BashCommandSplitter.isReadOnly("git diff --output=/tmp/x HEAD"), "实测会落盘");
        assertFalse(BashCommandSplitter.isReadOnly("git"), "裸 git 无子命令");
    }

    @Test
    @DisplayName("自带执行 / 删除逃逸口的命令不进只读白名单")
    void commandsWithExecEscapesAreNotWhitelisted() {
        assertFalse(BashCommandSplitter.isReadOnly("find . -name x -delete"), "find 有 -delete/-exec");
        assertFalse(BashCommandSplitter.isReadOnly("find . -name x"), "首词判不了 find 的谓词语言，整体不放行");
        assertFalse(BashCommandSplitter.isReadOnly("rg --pre=/tmp/evil pattern"), "rg 有 --pre/--hostname-bin");
        assertFalse(BashCommandSplitter.isReadOnly("env rm -rf /tmp/x"), "env 本身就是命令启动器");

        assertTrue(BashCommandSplitter.isReadOnly("printenv PATH"), "printenv 无法启动命令，保留");
        assertTrue(BashCommandSplitter.isReadOnly("grep -rn foo src"), "grep 无 exec 类开关，保留");
    }

    @Test
    @DisplayName("拆不动就问：${...} 花括号展开有可执行算子形态（bash ${x@P}）")
    void braceExpansionIsUnparseable() {
        assertFalse(BashCommandSplitter.split("echo ${x@P}").parseable());
        assertTrue(BashCommandSplitter.split("echo $HOME").parseable(), "裸 $VAR 只是取值，不会再解析出分隔符");
    }

    @Test
    @DisplayName("git 的写形态一律不算只读（实测：短选项捆绑 / 位置参数 / 等号形式都能绕过逐 token 检查）")
    void gitWriteFormsAreNotReadOnly() {
        assertFalse(BashCommandSplitter.isReadOnly("git branch -df victim"),
                "短选项捆绑 -df 会删未合并分支（实测数据丢失）");
        assertFalse(BashCommandSplitter.isReadOnly("git branch -Dq x"));
        assertFalse(BashCommandSplitter.isReadOnly("git branch -dr origin/foo"));
        assertFalse(BashCommandSplitter.isReadOnly("git branch evil"),
                "位置参数即创建分支");
        assertFalse(BashCommandSplitter.isReadOnly("git branch --set-upstream-to=origin/main"),
                "等号形式会写 branch.*.merge / .remote");
        assertFalse(BashCommandSplitter.isReadOnly("git remote add evil http://x"));
        // 仍应放行的只读形态
        assertTrue(BashCommandSplitter.isReadOnly("git status"));
        assertTrue(BashCommandSplitter.isReadOnly("git log --oneline -5"));
        assertTrue(BashCommandSplitter.isReadOnly("git diff HEAD~1"));
        assertFalse(BashCommandSplitter.isReadOnly("git diff --output=/tmp/x"));
    }

    @Test
    @DisplayName("file 移出只读白名单（file -C 会静默覆盖 cwd 下的 magic.mgc，实测 7.2MB）")
    void fileCommandIsNotReadOnly() {
        assertFalse(BashCommandSplitter.isReadOnly("file -C -m /dev/null"),
                "file -C 会编译并覆盖 magic.mgc（实测写入 7.2MB）");
        assertFalse(BashCommandSplitter.isReadOnly("file x.txt"),
                "整体移出白名单，不区分选项");
    }
}

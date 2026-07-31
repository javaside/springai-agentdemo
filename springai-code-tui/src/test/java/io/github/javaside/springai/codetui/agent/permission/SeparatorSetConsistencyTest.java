package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分隔符清单不得再有第二份。遍历<b>整个字符空间</b>而非固定样本——
 * {@link PredicateConsistencyTest} 的 {@code SAMPLES} 是字面量数组，
 * 只往一侧加字符它照样全绿，抓不到这类回归。
 *
 * <p>分隔符正是制造过 C1 与 {@code <(} 两个 Critical 的那份清单，
 * 漂移方向固定：只往 {@code isSeparatorChar} 加字符，splitter 变严而
 * {@code hasShellSeparator} 仍判「简单」，于是第 5 步放行、第 6 步永不运行。
 */
class SeparatorSetConsistencyTest {

    /**
     * 逐字符比对两处。
     *
     * <p><b>比较时须扣掉不可拆分构造那一项</b>：{@code hasShellSeparator} 是
     * 「分隔符 <b>或</b> 不可拆分构造」的并集，而反引号（96）本身就是单字符的不可拆分构造，
     * 它<b>不是</b>分隔符——直接断言两者相等会在 96 上恒红，与漂移无关。
     * 故对单字符先排除 {@code hasUnsplittableConstruct}，剩下的差异才是真漂移。
     */
    @Test
    @DisplayName("分隔符集合两处一致：遍历字符空间，不可拆分构造项除外")
    void separatorSetsCannotDrift() {
        for (char c = 0; c < 128; c++) {
            String s = String.valueOf(c);
            if (PermissionRule.hasUnsplittableConstruct(s)) {
                continue;                       // 反引号：是构造不是分隔符，另有清单管
            }
            assertEquals(BashCommandSplitter.isSeparatorChar(c),
                    PermissionRule.hasShellSeparator(s),
                    "分隔符集合两处不一致，字符：" + (int) c);
        }
    }

    /** 被跳过的字符只有反引号一个，且它必须仍被 {@code hasShellSeparator} 判为复合（别把洞跳掉了）。 */
    @Test
    @DisplayName("跳过项仅反引号，且它仍算复合命令")
    void onlyBacktickIsExcludedAndItStillCounts() {
        for (char c = 0; c < 128; c++) {
            String s = String.valueOf(c);
            if (PermissionRule.hasUnsplittableConstruct(s)) {
                assertEquals('`', c, "单字符的不可拆分构造应当只有反引号，字符：" + (int) c);
                assertTrue(PermissionRule.hasShellSeparator(s), "反引号必须仍算复合命令");
            }
        }
    }
}

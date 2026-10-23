package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** --resume 解析与前缀匹配（纯函数；main 只做接线与报错退出）。 */
class ResumeStartupTest {

    @Test
    void resumeFlagValue_readsBothSpellings_andTreatsDashAsMissingValue() {
        assertEquals("abc123", CodeTuiApplication.resumeFlagValue(new String[]{"--resume", "abc123"}));
        assertEquals("abc123", CodeTuiApplication.resumeFlagValue(new String[]{"--resume=abc123"}));
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"--resume", "--permission-mode"}),
                "下一个参数以 - 开头 → 当缺值（比 permission-mode 先例的 -- 前缀更宽，防 --resume -c 把 -c 当 id）");
        // 补充断言（变异实测：只测 -- 前缀杀不死 startsWith(\"-\") → startsWith(\"--\") 的回退改动）：
        // 单横线选项必须同样按缺值处理，否则 -c 被当会话 id、再被互斥分支兜住，报错误导。
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"--resume", "-c"}));
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"--resume"}), "尾部缺值");
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"-c", "--continue"}), "无 flag");
    }

    @Test
    void resolveResumeTarget_exactBeatsPrefix_uniquePrefixOk_ambiguousOrNoneNull() {
        List<String> ids = List.of("20260101T000000-aaaaaa", "20260101T000000-bbbbbb", "20260202T000000-aaaaaa");
        assertEquals("20260202T000000-aaaaaa",
                CodeTuiApplication.resolveResumeTarget(ids, "20260202T000000-aaaaaa"), "精确命中");
        assertEquals("20260101T000000-aaaaaa",
                CodeTuiApplication.resolveResumeTarget(ids, "20260101T000000-a"), "唯一前缀");
        assertNull(CodeTuiApplication.resolveResumeTarget(ids, "20260101T000000"), "多命中 → null（不猜，让用户补长）");
        assertNull(CodeTuiApplication.resolveResumeTarget(ids, "2099"), "零命中 → null");
        assertNull(CodeTuiApplication.resolveResumeTarget(List.of(), "x"), "空候选");
    }
}

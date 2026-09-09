package io.github.javaside.springai.codetui.ui.output;

import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PhysicalOutputQueue.drain() 的 alreadyWrittenThisBatch 豁免语义：
 * 「前 2 行免时间检查」必须按整批累计判断，不是按每次 drain() 调用各自的局部计数判断。
 * 见 docs/superpowers/specs/2026-09-09-code-tui-drain-budget-exemption-fix-design.md。
 */
class PhysicalOutputQueueTest {

    /** 记录型出口：本测试全部走纯文本分支。 */
    private static final class RecordingSink implements PhysicalOutputQueue.PhysicalSink {
        final List<String> lines = new ArrayList<>();
        @Override public void printlnPlain(String line, Object raw) { lines.add(line); }
        @Override public void printlnStyled(Text line, Object raw) {
            throw new UnsupportedOperationException("本测试不涉及带样式分支");
        }
    }

    /** 产出固定条数纯文本行（"line0".."line{count-1}"）的游标，每次 next() 摊还 O(1)。 */
    private static OutputCursor linesCursor(int count) {
        return new OutputCursor() {
            private int at = 0;
            @Override public boolean hasNext() { return at < count; }
            @Override public PhysicalOutputQueue.PhysicalLine next() {
                if (at >= count) return null;
                return PhysicalOutputQueue.PhysicalLine.plain("line" + (at++));
            }
        };
    }

    private static PhysicalOutputQueue queueWith(OutputCursor cursor) {
        PhysicalOutputQueue q = new PhysicalOutputQueue(rows -> {
            throw new UnsupportedOperationException("本测试不使用 streaming lines 入口");
        });
        q.enqueue(v -> cursor);
        return q;
    }

    private static long expiredDeadline() {
        return System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(1);
    }

    @Test
    void firstCallInBatch_stillGetsTwoLineGrace_evenPastDeadline() {
        // alreadyWrittenThisBatch=0：批的第一次调用，deadline 已过期，仍应至少写 2 行——
        // 这是「forward progress」保证的本意（慢机器不能卡在 0 行），修复后必须保留。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();

        PhysicalOutputQueue.BatchResult result = q.drain(300, expiredDeadline(), 0, sink);

        assertEquals(2, result.rowsWritten(), "批的第一段即使 deadline 已过期也该写够 2 行");
        assertTrue(result.timeBudgetExhausted());
        assertEquals(List.of("line0", "line1"), sink.lines);
    }

    @Test
    void laterCallInBatch_getsNoGrace_evenWithJustOnePriorRow() {
        // alreadyWrittenThisBatch=1：批内更早的 drain() 调用已经写过 1 行（离门槛只差 1），
        // 这次调用（模拟 CodeTuiView 一批里的第二/第三段）不该重新获得「前 2 行免检」——
        // 这是本次要修的缺口：修复前这里的 rowsWritten 会是 2（每次调用各自重新豁免）。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();

        PhysicalOutputQueue.BatchResult result = q.drain(300, expiredDeadline(), 1, sink);

        assertEquals(1, result.rowsWritten(),
                "批内已写 1 行时，本次调用只该再写 1 行就停——不是重新豁免 2 行");
        assertTrue(result.timeBudgetExhausted());
        assertEquals(List.of("line0"), sink.lines);
    }

    @Test
    void deadlineNotYetExpired_writesUpToRowBudget_regardlessOfAlreadyWritten() {
        // deadline 远未到时，alreadyWrittenThisBatch 不该提前掐断——只受行数预算约束。
        PhysicalOutputQueue q = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();
        long farFutureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        PhysicalOutputQueue.BatchResult result = q.drain(300, farFutureDeadline, 2, sink);

        assertEquals(5, result.rowsWritten(), "deadline 远未到时应该写完游标里的全部内容");
        assertFalse(result.timeBudgetExhausted());
        assertFalse(result.remaining());
    }

    @Test
    void twoSequentialCallsSharingCallerTrackedTotal_mirrorsCodeTuiViewUsage() {
        // 端到端风格：模拟 CodeTuiView 一批里连续两次 drainQueuedOutput 调用，调用方用
        // 累计值（对应 CodeTuiView.batchRowsUsed 字段）喂给下一次调用——第二次不该重新豁免。
        long deadline = expiredDeadline();

        PhysicalOutputQueue firstSegment = queueWith(linesCursor(5));
        RecordingSink sink = new RecordingSink();
        PhysicalOutputQueue.BatchResult first = firstSegment.drain(300, deadline, 0, sink);
        assertEquals(2, first.rowsWritten());

        int batchRowsUsed = first.rowsWritten();   // CodeTuiView 的既有累计字段语义
        PhysicalOutputQueue secondSegment = queueWith(linesCursor(5));
        PhysicalOutputQueue.BatchResult second =
                secondSegment.drain(300, deadline, batchRowsUsed, sink);

        assertEquals(1, second.rowsWritten(),
                "第二段不该独立于第一段重新获得 2 行豁免——这是本次修复要钉住的缺口");
    }
}

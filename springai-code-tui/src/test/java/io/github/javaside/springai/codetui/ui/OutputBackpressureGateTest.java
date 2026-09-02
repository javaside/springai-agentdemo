package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import io.github.javaside.springai.codetui.ui.update.UiUpdateCoordinator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「输出时打字卡死」根治的应用层背压门控（fix：pty 异步写接线）。
 *
 * <p>根因：macOS pty 内核写缓冲仅 ~1-2 KiB；终端读端停摆（IME 合成 / 渲染积压）时
 * write(2) 可挂数秒到无限期。库层已把写剥离到 AsyncPtyWriter 守护线程（patch 模块），
 * 渲染线程不再睡死；应用层的配套纪律是：<b>pty writer 饱和时本批停止产出新输出</b>
 * （pending/流式行留在真相源里），等「排空唤醒」驱动的后续批接续——否则渲染线程会
 * 无限往 display 的延迟队列里攒批，内存无界。
 */
class OutputBackpressureGateTest {

    /** 测试桩 SubmitHandler：只实现必需的 submit(String)。 */
    private static final class Stub implements SubmitHandler {
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentModel() { return "test-model"; }
    }

    private CodeTuiView newView(Path root) {
        return new CodeTuiView(new ConversationState(), new Stub(), root);
    }

    /**
     * 核心回归钉：writer 饱和（背压闸关闭）时，processUpdates 不得从 state 取出
     * pending——内容必须原样留在真相源里等下一批。
     */
    @Test
    void saturatedWriterGatesPendingIntake(@org.junit.jupiter.api.io.TempDir Path root) {
        CodeTuiView view = newView(root);
        ConversationState state = view.stateForTest();

        for (int i = 0; i < 60; i++) {
            state.pushInfo("pending-" + i);
        }
        view.setOutputBackpressuredForTest(true);

        UiUpdateCoordinator.UpdateResult r = view.processUpdatesForTest(UiDirty.OUTPUT);

        assertTrue(state.hasPendingOutput(), "饱和时 pending 必须原样留在 state（一条都不取）");
        assertEquals(0, view.pendingIntakeCountForTest(), "饱和时 pending 转入数必须为 0");
        assertTrue(view.outputQueueEmptyForTest(), "饱和时不得向输出队列新增任何内容");
    }

    /** 闸开着（正常路径）：pending 照常转入并按预算 drain——确保门控没有误伤正常输出。 */
    @Test
    void openGateDrainsNormally(@org.junit.jupiter.api.io.TempDir Path root) {
        CodeTuiView view = newView(root);
        ConversationState state = view.stateForTest();
        for (int i = 0; i < 5; i++) {
            state.pushInfo("pending-" + i);
        }

        view.processUpdatesForTest(UiDirty.OUTPUT);

        assertFalse(state.hasPendingOutput(), "闸开时 pending 应被消费");
        assertTrue(view.pendingIntakeCountForTest() >= 5,
                "5 条 pending 应全部转入（实际 " + view.pendingIntakeCountForTest() + "）");
    }

    /** 饱和批的 follow-up：声明 outputRemaining（延迟批在途 = 存量未清空），coordinator 排 continuation。 */
    @Test
    void saturatedBatchDeclaresOutputRemaining(@org.junit.jupiter.api.io.TempDir Path root) {
        CodeTuiView view = newView(root);
        ConversationState state = view.stateForTest();
        for (int i = 0; i < 10; i++) {
            state.pushInfo("pending-" + i);
        }
        view.setOutputBackpressuredForTest(true);

        UiUpdateCoordinator.UpdateResult r = view.processUpdatesForTest(UiDirty.OUTPUT);

        assertTrue(r.outputRemaining(), "饱和（背压）期间必须声明 outputRemaining 驱动 continuation");
    }

    /** 闸恢复 + 唤醒批：pending 正常消费——背压解除后输出接续，不丢内容。 */
    @Test
    void recoveredGateDrainsBacklog(@org.junit.jupiter.api.io.TempDir Path root) {
        CodeTuiView view = newView(root);
        ConversationState state = view.stateForTest();
        for (int i = 0; i < 10; i++) {
            state.pushInfo("pending-" + i);
        }
        view.setOutputBackpressuredForTest(true);
        view.processUpdatesForTest(UiDirty.OUTPUT);
        assertTrue(state.hasPendingOutput());

        view.setOutputBackpressuredForTest(false);
        view.processUpdatesForTest(UiDirty.OUTPUT);

        assertFalse(state.hasPendingOutput(), "闸恢复后积压应被接续消费");
        assertTrue(view.pendingIntakeCountForTest() >= 10,
                "积压 10 条应全部转入（实际 " + view.pendingIntakeCountForTest() + "）");
    }
    /**
     * 设备死亡时闸必须打开（审核 M1 的应用层钉）：writer 死后 isSaturated 恒 false——
     * pending 照常消费（流进 void 是可接受的：终端已没了），绝不能因饱和闸永久关死
     * 而 freeze-forever。
     */
    @Test
    void deadWriterOpensGateAndDrains(@org.junit.jupiter.api.io.TempDir Path root) {
        CodeTuiView view = newView(root);
        ConversationState state = view.stateForTest();
        for (int i = 0; i < 10; i++) {
            state.pushInfo("pending-" + i);
        }
        // 模拟设备死亡：闸测试位复位（生产 isSaturated 在 dead 时恒 false）。
        view.setOutputBackpressuredForTest(true);
        view.processUpdatesForTest(UiDirty.OUTPUT);
        assertTrue(state.hasPendingOutput(), "饱和期先攒住");

        view.setOutputBackpressuredForTest(false);   // 死亡态：闸打开
        view.processUpdatesForTest(UiDirty.OUTPUT);
        assertFalse(state.hasPendingOutput(), "设备死亡/闸开后 pending 必须照常消费（不得 freeze-forever）");
    }
}

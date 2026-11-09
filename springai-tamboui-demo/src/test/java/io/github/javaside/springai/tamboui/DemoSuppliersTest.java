package io.github.javaside.springai.tamboui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 菜单的示例供给方式测试：每次运行示例都取<b>新实例</b>。
 *
 * <p>背景（终审 Important 修复）：示例②③④的字段（帧号/仪表盘状态/流式会话）是可变状态，
 * 若菜单持有共享实例，一次运行残留的状态会泄进下一次（示例④流式中途退出后重跑会永久
 * 卡在「生成中」）。用 Supplier 每次取新实例，让这类残留从结构上不可能发生。</p>
 */
class DemoSuppliersTest {

    @Test
    void eachInvocationGetsFreshInstance() {
        List<Supplier<TambouiDemo>> suppliers = TambouiDemoApplication.demoSuppliers();
        for (Supplier<TambouiDemo> supplier : suppliers) {
            assertNotSame(supplier.get(), supplier.get(), "每次运行必须取新实例，否则可变状态跨运行残留");
        }
    }

    @Test
    void fourDemosInTeachingOrder() {
        List<Supplier<TambouiDemo>> suppliers = TambouiDemoApplication.demoSuppliers();
        assertEquals(4, suppliers.size());
        assertEquals(1, suppliers.get(0).get().order());
        assertEquals(2, suppliers.get(1).get().order());
        assertEquals(3, suppliers.get(2).get().order());
        assertEquals(4, suppliers.get(3).get().order());   // 旧聊天示例，第 6 课将替换
    }
}

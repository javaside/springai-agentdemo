package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PermissionModeTest {

    @Test
    @DisplayName("无启动参数：三档循环 DEFAULT → ACCEPT_EDITS → PLAN → DEFAULT，BYPASS 不混入")
    void cyclesThreeWithoutBypass() {
        PermissionMode m = PermissionMode.DEFAULT;
        m = m.next(false);
        assertEquals(PermissionMode.ACCEPT_EDITS, m);
        m = m.next(false);
        assertEquals(PermissionMode.PLAN, m);
        m = m.next(false);
        assertEquals(PermissionMode.DEFAULT, m, "无 --dangerously-skip-permissions 时不得走到 BYPASS");

        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(false));
    }

    @Test
    @DisplayName("带启动参数：四档循环，BYPASS 排在 PLAN 之后")
    void cyclesFourWithBypass() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next(true));
        assertEquals(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS.next(true));
        assertEquals(PermissionMode.BYPASS, PermissionMode.PLAN.next(true));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next(true));
    }

    @Test
    @DisplayName("每档都有互不相同的非空标签（状态栏与面板直接显示它）")
    void labelsAreDistinct() {
        for (PermissionMode a : PermissionMode.values()) {
            assertNotEquals("", a.label(), a + " 的 label 不能为空");
            for (PermissionMode b : PermissionMode.values()) {
                if (a != b) {
                    assertNotEquals(a.label(), b.label(), a + " 与 " + b + " 标签撞了，用户分不清在哪一档");
                }
            }
        }
    }
}

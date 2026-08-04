package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionModeTest {

    @Test
    @DisplayName("四档平权循环：默认 → 自动接受编辑 → 计划模式 → 跳过权限检查 → 默认")
    void cyclesFourModes() {
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.DEFAULT.next());
        assertEquals(PermissionMode.PLAN, PermissionMode.ACCEPT_EDITS.next());
        assertEquals(PermissionMode.BYPASS, PermissionMode.PLAN.next());
        assertEquals(PermissionMode.DEFAULT, PermissionMode.BYPASS.next());
    }

    /**
     * 只断言「按四次回到起点」是不够的：一个把 PLAN 直接接回 DEFAULT 的三档实现，
     * 按四次同样回得到 DEFAULT（DEFAULT→ACCEPT→PLAN→DEFAULT→ACCEPT，不对，
     * 但按三次就回来了、第四次在 ACCEPT）——所以还要断言四次经过的档位<b>互不相同</b>，
     * 这条才真正钉住「四档都在环上」。
     */
    @Test
    @DisplayName("连按四次遍历全部四档，一个不漏、一个不重")
    void fourPressesVisitEveryMode() {
        Set<PermissionMode> seen = new LinkedHashSet<>();
        PermissionMode m = PermissionMode.DEFAULT;
        for (int i = 0; i < 4; i++) {
            m = m.next();
            seen.add(m);
        }
        assertEquals(PermissionMode.DEFAULT, m, "四次之后必须回到起点");
        assertEquals(4, seen.size(), "四次必须经过四个互不相同的档位，实际经过：" + seen);
        assertTrue(seen.contains(PermissionMode.BYPASS),
                "BYPASS 必须在环上——它不再需要任何启动参数");
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

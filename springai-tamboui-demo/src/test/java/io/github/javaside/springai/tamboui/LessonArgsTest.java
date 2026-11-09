package io.github.javaside.springai.tamboui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 子进程启动参数解析测试：菜单以 `--lesson N` 在独立 JVM 里运行每一课
 * （TUI 应用独占进程——上游 jline 终端状态无法在同进程内多次进出干净复用，
 * 实测同进程连跑会在菜单处丢键，见 TambouiDemoApplication 注释）。
 */
class LessonArgsTest {

    @Test
    void parsesLessonNumber() {
        assertEquals(3, LessonArgs.parse(new String[]{"--lesson", "3"}));
        assertEquals(1, LessonArgs.parse(new String[]{"--lesson", "1"}));
    }

    @Test
    void menuModeWhenNoArgs() {
        assertEquals(-1, LessonArgs.parse(new String[]{}));
    }

    @Test
    void rejectsMalformed() {
        assertEquals(-1, LessonArgs.parse(new String[]{"--lesson"}));           // 缺数字
        assertEquals(-1, LessonArgs.parse(new String[]{"--lesson", "x"}));      // 非数字
        assertEquals(-1, LessonArgs.parse(new String[]{"--lesson", "0"}));      // 0 是退出不是课
        assertEquals(-1, LessonArgs.parse(new String[]{"--lesson", "9"}));      // 越界（共 6 课）
        assertEquals(-1, LessonArgs.parse(new String[]{"--lesson", "-1"}));     // 负数
    }
}

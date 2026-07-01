package com.example.springai.codetui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.example.springai.codetui.CodeTuiApplication.isCleared;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 里程碑4：钉住启动安全门的放行逻辑（纯函数，不依赖 stdin/TUI）。
 *
 * <p>放行条件二选一：环境变量 {@code CODE_TUI_I_UNDERSTAND=1}（跳过交互），
 * 或交互式回答以 y/Y 开头（去除首尾空白后大小写不敏感）。
 */
class CodeTuiApplicationGateTest {

    @Test
    @DisplayName("CODE_TUI_I_UNDERSTAND=1 时放行，无需交互答案")
    void envUnderstand_1_clearsWithoutInteractiveAnswer() {
        assertTrue(isCleared("1", null));
    }

    @Test
    @DisplayName("交互答案 y 放行（env 缺省）")
    void interactiveAnswer_y_clears() {
        assertTrue(isCleared(null, "y"));
    }

    @Test
    @DisplayName("env 非 1（如 0）时仍看交互答案，y 放行")
    void envUnderstand_0_stillClearsViaInteractiveY() {
        assertTrue(isCleared("0", "y"));
    }

    @Test
    @DisplayName("交互答案大小写不敏感：Y 放行")
    void interactiveAnswer_Y_uppercase_clears() {
        assertTrue(isCleared(null, "Y"));
    }

    @Test
    @DisplayName("env 和交互答案都缺省时不放行")
    void bothMissing_doesNotClear() {
        assertFalse(isCleared(null, null));
    }

    @Test
    @DisplayName("交互答案 n 不放行")
    void interactiveAnswer_n_doesNotClear() {
        assertFalse(isCleared(null, "n"));
    }

    @Test
    @DisplayName("交互答案空字符串不放行")
    void interactiveAnswer_empty_doesNotClear() {
        assertFalse(isCleared(null, ""));
    }
}

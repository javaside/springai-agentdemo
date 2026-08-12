package dev.tamboui.inline;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynchronizedOutputTest {

    @Test
    void neverLeavesPayloadUntouched() {
        assertEquals("patch", SynchronizedOutput.from(Map.of(), "never").wrap("patch"));
    }

    @Test
    void alwaysWrapsWithMode2026() {
        assertEquals("\u001b[?2026hpatch\u001b[?2026l",
                SynchronizedOutput.from(Map.of(), "always").wrap("patch"));
    }

    @Test
    void autoEnablesOnlyForKnownTerminalIdentity() {
        assertTrue(SynchronizedOutput.from(Map.of("WT_SESSION", "x"), "auto").enabled());
        assertTrue(SynchronizedOutput.from(Map.of("TERM_PROGRAM", "WezTerm"), "auto").enabled());
        assertTrue(SynchronizedOutput.from(Map.of("TERM_PROGRAM", "iTerm.app"), "auto").enabled());
        assertTrue(SynchronizedOutput.from(Map.of("KITTY_WINDOW_ID", "1"), "auto").enabled());
        assertFalse(SynchronizedOutput.from(Map.of("TERM", "xterm-256color"), "auto").enabled());
    }

    @Test
    void invalidPropertySafelyFallsBackToAuto() {
        assertFalse(SynchronizedOutput.from(Map.of(), "unexpected").enabled());
    }
}

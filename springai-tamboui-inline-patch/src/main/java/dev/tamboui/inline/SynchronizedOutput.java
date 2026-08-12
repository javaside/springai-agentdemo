/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.inline;

import java.util.Locale;
import java.util.Map;

/** Non-blocking policy for DEC private mode 2026 synchronized output. */
final class SynchronizedOutput {
    enum Mode { AUTO, ALWAYS, NEVER }

    private static final String BEGIN = "\u001b[?2026h";
    private static final String END = "\u001b[?2026l";

    private final boolean enabled;

    private SynchronizedOutput(boolean enabled) {
        this.enabled = enabled;
    }

    static SynchronizedOutput from(Map<String, String> env, String property) {
        Mode mode;
        try {
            mode = Mode.valueOf((property == null ? "auto" : property).strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            mode = Mode.AUTO;
        }
        return new SynchronizedOutput(switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> knownTerminal(env);
        });
    }

    static SynchronizedOutput systemDefault() {
        return from(System.getenv(), System.getProperty("codetui.syncOutput", "auto"));
    }

    boolean enabled() {
        return enabled;
    }

    String wrap(String payload) {
        return enabled && !payload.isEmpty() ? BEGIN + payload + END : payload;
    }

    private static boolean knownTerminal(Map<String, String> env) {
        if (present(env, "WT_SESSION") || present(env, "KITTY_WINDOW_ID")) return true;
        String program = env.getOrDefault("TERM_PROGRAM", "");
        return program.equalsIgnoreCase("WezTerm") || program.equalsIgnoreCase("iTerm.app");
    }

    private static boolean present(Map<String, String> env, String key) {
        String value = env.get(key);
        return value != null && !value.isBlank();
    }
}

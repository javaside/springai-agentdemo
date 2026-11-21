/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui.event;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.OptionalInt;

import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;

/**
 * Parses raw terminal input into typed {@link Event} objects.
 * <p>
 * Handles escape sequences for standard terminal input, mouse events (SGR mode,
 * and the X10 format of terminals without it), and progressive keyboard enhancements
 * (Kitty keyboard protocol {@code CSI u}).
 *
 * <p><b>springai-tamboui-inline-patch shadow 类</b>（与 {@code InlineTuiRunner} 同机制：
 * code-tui 的 Class-Path 顺序保证本 jar 先于 tamboui-tui 加载）。以 0.7.0 上游为底座
 * rebase（2026-11-21），Kitty/X10 与输入解码修复（#611/#517/#481）全量继承；与上游的
 * 差异都在粘贴链路：① {@link #readPasteContent} 把上游「单次 50ms 超时即放弃」改为
 * <b>累计静默 500ms</b> 才放弃——大粘贴（几百 KB 日志）时终端分块灌 pty、块间间隙一旦
 * 超过 50ms，粘贴事件被提前掐断，剩余字节回到普通按键解析：日志里的 {@code \n} 变
 * Enter、在 code-tui 里触发「贴入即分批自动发送」（人手敲不出 ESC[200~ 起始标记，
 * 无副作用；EOF 时上游 0.7 抛 {@code EOFException}，本 shadow 返回已读部分，下次
 * 读取仍会结束）；② 批量快路径扫到终止符后，同一读取块里<b>溢出</b>的字节回灌给
 * 下一次解析（{@code OVERSHOOT_*} / {@code BacklogBackend}）——Terminal.app 多文件
 * 拖拽每文件发一条独立括号粘贴、背靠背连发（2026-10-14 od 取证），不回灌的话后续
 * 整条粘贴被静默丢弃，实报症状「一次拖 5 张图只显示 1-2 张」。行为契约见
 * {@code EventParserPasteTest} / {@code EventParserBulkPasteTest}。库升级时须重对本类做 diff。
 */
public final class EventParser {

    private static final int ESC = 27;
    private static final int PEEK_TIMEOUT = 50;

    /**
     * 括号粘贴的<b>累计静默预算</b>：读超时后只要总静默没超过这个窗口就继续等下一块。
     * 上游是单次 50ms 超时即放弃。取 500ms：真机大粘贴的块间间隙（pty 缓冲满 → 终端写
     * 阻塞 → 恢复）实测远小于此；而畸形粘贴（有 ESC[200~ 无 ESC[201~）最多也只多阻塞
     * 这 500ms，输入线程不挂死。每次收到数据即重置计时。
     */
    private static final long PASTE_QUIET_BUDGET_NANOS = 500_000_000L;
    /**
     * 原始流轮询间隔：macOS pty 输入队列仅 ~1KB，available() 单轮最多见 1KB——
     * 轮询间隔即吞吐上限（1KB/间隔）。50ms → 20KB/s（4MB 粘贴 223s，实测翻车），
     * 1ms → ~1MB/s，远超任何终端的粘贴投递速率，且放弃语义仍由累计静默预算保证。
     */
    private static final long RAW_POLL_MS = 1;

    /**
     * 批量粘贴读<b>溢出</b>的已解码字符：批量块按 8KB 整读、扫到 {@code ESC[201~} 就收手，
     * 同块里排在终止符之后的字节（Terminal.app 多文件拖拽实测：每文件一条独立括号粘贴
     * 背靠背 {@code ESC[200~p ESC[201~ESC[200~p...}，2026-10-14 od 取证）此前被整段静默
     * 丢弃——实报症状「拖 5 张图只显示 2 张」，幸存哪几张取决于终端写入与轮询的块边界。
     * 这里存已解码的字符；配 {@link #OVERSHOOT_CARRY} 存未解码完的多字节残尾。
     * 输入单线程，无需加锁。
     */
    private static final java.util.ArrayDeque<Integer> OVERSHOOT_CHARS = new java.util.ArrayDeque<>();

    /** 溢出字节里未解码完的 UTF-8 残尾（可能属于下一条粘贴的首字符），按原始字节保留。 */
    private static final java.io.ByteArrayOutputStream OVERSHOOT_CARRY =
            new java.io.ByteArrayOutputStream();

    /** 把批量读取扫到终止符后剩下的字符与残尾字节入库，留给下一次 {@link #readEvent}。 */
    private static void stashOvershoot(java.nio.CharBuffer out, java.nio.ByteBuffer carry) {
        while (out.hasRemaining()) {
            OVERSHOOT_CHARS.add((int) out.get());
        }
        byte[] rest = new byte[carry.remaining()];
        carry.get(rest);
        OVERSHOOT_CARRY.write(rest, 0, rest.length);
    }

    /**
     * 「先暂存、后真实流」的委托 Backend：readEvent 检测到有溢出存量时用它包住真实
     * backend，解析器照常经 read/peek 取字符，感知不到来源切换。反射链 {@code
     * rawBulkStream} 对本类必然失配（没有 {@code reader} 字段），溢出粘贴的正文因此
     * 走逐字符慢路径——体量只是几条文件路径，慢路径的吞吐绰绰有余。
     */
    private static final class BacklogBackend implements Backend {
        private final Backend delegate;
        private final java.nio.charset.CharsetDecoder decoder =
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);

        BacklogBackend(Backend delegate) {
            this.delegate = delegate;
        }

        /** 溢出字符 → 溢出残尾字节（残尾解不动就替换消费，绝不卡死后续读取）。 */
        private int nextStashed() {
            Integer c = OVERSHOOT_CHARS.poll();
            if (c != null) return c;
            if (OVERSHOOT_CARRY.size() == 0) return -2;
            byte[] bytes = OVERSHOOT_CARRY.toByteArray();
            OVERSHOOT_CARRY.reset();
            java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(bytes);
            java.nio.CharBuffer out = java.nio.CharBuffer.allocate(bytes.length + 2);
            decoder.decode(in, out, true);          // REPLACE：切在多字节中间的残尾不阻塞
            out.flip();
            if (!out.hasRemaining()) return -2;
            int first = out.get();
            while (out.hasRemaining()) OVERSHOOT_CHARS.add((int) out.get());
            return first;
        }

        @Override public int read(int timeoutMs) throws IOException {
            if (!OVERSHOOT_CHARS.isEmpty() || OVERSHOOT_CARRY.size() > 0) {
                int c = nextStashed();
                if (c != -2) return c;
            }
            return delegate.read(timeoutMs);
        }

        @Override public int peek(int timeoutMs) throws IOException {
            if (!OVERSHOOT_CHARS.isEmpty()) return OVERSHOOT_CHARS.peekFirst();
            if (OVERSHOOT_CARRY.size() > 0) {
                int c = nextStashed();
                if (c != -2) {
                    OVERSHOOT_CHARS.addFirst(c);
                    return c;
                }
            }
            return delegate.peek(timeoutMs);
        }

        @Override public void draw(dev.tamboui.buffer.DiffResult diff) throws IOException { delegate.draw(diff); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void clear() throws IOException { delegate.clear(); }
        @Override public dev.tamboui.layout.Size size() throws IOException { return delegate.size(); }
        @Override public void showCursor() throws IOException { delegate.showCursor(); }
        @Override public void hideCursor() throws IOException { delegate.hideCursor(); }
        @Override public dev.tamboui.layout.Position getCursorPosition() throws IOException { return delegate.getCursorPosition(); }
        @Override public void setCursorPosition(dev.tamboui.layout.Position position) throws IOException { delegate.setCursorPosition(position); }
        @Override public void enterAlternateScreen() throws IOException { delegate.enterAlternateScreen(); }
        @Override public void leaveAlternateScreen() throws IOException { delegate.leaveAlternateScreen(); }
        @Override public void enableRawMode() throws IOException { delegate.enableRawMode(); }
        @Override public void disableRawMode() throws IOException { delegate.disableRawMode(); }
        @Override public void writeRaw(byte[] data) throws IOException { delegate.writeRaw(data); }
        @Override public void onResize(Runnable handler) { delegate.onResize(handler); }
        @Override public void close() throws IOException { delegate.close(); }
    }


    private EventParser() {
    }

    /**
     * Reads and parses the next event from the backend using the default bindings.
     *
     * @param backend the terminal backend
     * @param timeout timeout in milliseconds for the initial read
     * @return the parsed event, or null if no event was available
     * @throws EOFException if terminal input ends
     * @throws IOException  if an I/O error occurs
     */
    public static Event readEvent(Backend backend, int timeout) throws IOException {
        return readEvent(backend, timeout, BindingSets.defaults());
    }

    /**
     * Reads and parses the next event from the backend.
     *
     * @param backend  the terminal backend
     * @param timeout  timeout in milliseconds for the initial read
     * @param bindings the bindings for event semantic action matching
     * @return the parsed event, or null if no event was available
     * @throws EOFException if terminal input ends
     * @throws IOException  if an I/O error occurs
     */
    public static Event readEvent(Backend backend, int timeout, Bindings bindings) throws IOException {
        // SHADOW：上一次批量粘贴读溢出的字节（见 OVERSHOOT_*）先于真实流消费——批量块不认事件
        // 边界，终止符之后同块里的字节（Terminal.app 多文件拖拽的下一条粘贴）只能先
        // 存起来，在这里以「先暂存、后流」的顺序回灌给解析器。
        if (!OVERSHOOT_CHARS.isEmpty() || OVERSHOOT_CARRY.size() > 0) {
            backend = new BacklogBackend(backend);
        }

        int c = backend.read(timeout);

        if (c == -2) {
            // Timeout - no input available
            return null;
        }

        if (c == -1) {
            throw new EOFException("Terminal input closed");
        }

        return parseInput(c, backend, bindings);
    }

    private static Event parseInput(int c, Backend backend, Bindings bindings) throws IOException {
        if (c == ESC) {
            return parseEscapeSequence(backend, bindings);
        }

        // Control characters
        if (c < 32) {
            return parseControlChar(c, bindings);
        }

        // DEL key
        if (c == 127) {
            return KeyEvent.ofKey(KeyCode.BACKSPACE, bindings);
        }

        // Any printable character (ASCII or full Unicode code point from backend)
        return KeyEvent.ofChar(c, bindings);
    }

    private static KeyEvent parseControlChar(int c, Bindings bindings) {
        switch (c) {
            case 0:
                return KeyEvent.ofChar(' ', KeyModifiers.CTRL, bindings); // Ctrl+Space (NUL)
            case 3:
                return KeyEvent.ofChar('c', KeyModifiers.CTRL, bindings); // Ctrl+C
            case 8:
                return KeyEvent.ofKey(KeyCode.BACKSPACE, bindings); // Backspace (BS on Windows)
            case 9:
                return KeyEvent.ofKey(KeyCode.TAB, bindings); // Tab
            case 10:
            case 13:
                return KeyEvent.ofKey(KeyCode.ENTER, bindings); // Enter (LF or CR)
            case 27:
                return KeyEvent.ofKey(KeyCode.ESCAPE, bindings); // Escape (standalone)
            case 28:
                return KeyEvent.ofChar('\\', KeyModifiers.CTRL, bindings); // Ctrl+\ (FS)
            case 29:
                return KeyEvent.ofChar(']', KeyModifiers.CTRL, bindings); // Ctrl+] (GS)
            case 30:
                return KeyEvent.ofChar('^', KeyModifiers.CTRL, bindings); // Ctrl+^ (RS)
            case 31:
                return KeyEvent.ofChar('/', KeyModifiers.CTRL, bindings); // Ctrl+/ (US)
            default:
                if (c >= 1 && c <= 26) {
                    char letter = (char) ('a' + c - 1);
                    return KeyEvent.ofChar(letter, KeyModifiers.CTRL, bindings);
                }
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }
    }

    private static KeyEvent withAlt(KeyEvent event, Bindings bindings) {
        KeyModifiers modifiers = KeyModifiers.of(event.hasCtrl(), true, event.hasShift());
        if (event.code() == KeyCode.CHAR) {
            return KeyEvent.ofChar(event.codePoint(), modifiers, bindings);
        }
        return KeyEvent.ofKey(event.code(), modifiers, bindings);
    }

    private static Event parseEscapeSequence(Backend backend, Bindings bindings) throws IOException {
        int next = backend.peek(PEEK_TIMEOUT);

        if (next == -2 || next == -1) {
            // Standalone ESC key
            return KeyEvent.ofKey(KeyCode.ESCAPE, bindings);
        }

        if (next == ESC) {
            // The second ESC starts the next key: an Escape of its own, or a sequence such as ESC [ A
            // (Alt+Up from a terminal that sends Alt as an ESC prefix, or Escape followed quickly by
            // Up). Leave it unread for the next event, so the rest of the sequence is not typed as text.
            return KeyEvent.ofKey(KeyCode.ESCAPE, bindings);
        }

        if (next == '[') {
            backend.read(PEEK_TIMEOUT); // consume '['
            return parseCSI(backend, bindings);
        }

        if (next == 'O') {
            backend.read(PEEK_TIMEOUT); // consume 'O'
            return parseSS3(backend, bindings);
        }

        if (next > 0 && next < 32) {
            // Alt with a control key, sent as an ESC prefix: ESC CR/LF is Alt+Enter, ESC TAB is
            // Alt+Tab, ESC BS is Alt+Backspace, and ESC 0x01 is Ctrl+Alt+A. Reuse the control-char
            // mapping and layer Alt on top so every C0 key keeps its meaning and existing modifiers.
            backend.read(PEEK_TIMEOUT); // consume the control character
            return withAlt(parseControlChar(next, bindings), bindings);
        }

        // Alt+key
        backend.read(PEEK_TIMEOUT); // consume the character
        if (next == 127) {
            // ESC DEL: Alt+Backspace on terminals that send Backspace as DEL (0x7F).
            return KeyEvent.ofKey(KeyCode.BACKSPACE, KeyModifiers.ALT, bindings);
        }
        if (next >= 32) {
            return KeyEvent.ofChar(next, KeyModifiers.ALT, bindings);
        }

        return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
    }

    private static Event parseCSI(Backend backend, Bindings bindings) throws IOException {
        int c = backend.read(PEEK_TIMEOUT);
        if (c == -2 || c == -1) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        // Check for mouse event (SGR extended mode: ESC [ < ...)
        if (c == '<') {
            return parseMouseSGR(backend, bindings);
        }

        // Single-character terminators without parameters
        if (c >= 0x40 && c <= 0x7E) {
            switch (c) {
                case 'A':
                    return KeyEvent.ofKey(KeyCode.UP, bindings);
                case 'B':
                    return KeyEvent.ofKey(KeyCode.DOWN, bindings);
                case 'C':
                    return KeyEvent.ofKey(KeyCode.RIGHT, bindings);
                case 'D':
                    return KeyEvent.ofKey(KeyCode.LEFT, bindings);
                case 'H':
                    return KeyEvent.ofKey(KeyCode.HOME, bindings);
                case 'F':
                    return KeyEvent.ofKey(KeyCode.END, bindings);
                case 'E':
                    return KeyEvent.ofKey(KeyCode.KP_BEGIN, bindings);
                case 'Z':
                    // Shift+Tab (backtab) - ESC[Z
                    return KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT, bindings);
                case 'P':
                    // Legacy xterm form of F1 when no modifiers are present (CSI P).
                    // Terminals emit this when NumLock is off and no modifier is held.
                    return KeyEvent.ofKey(KeyCode.F1, bindings);
                case 'Q':
                    // Legacy xterm form of F2 (CSI Q).
                    return KeyEvent.ofKey(KeyCode.F2, bindings);
                case 'S':
                    // Legacy xterm form of F4 (CSI S). Note: CSI R is intentionally not
                    // mapped here because it clashes with the cursor-position report.
                    return KeyEvent.ofKey(KeyCode.F4, bindings);
                case '[':
                    // Linux console sends F1-F5 as the double-bracket form ESC[[A..ESC[[E.
                    return parseLinuxConsoleFunctionKey(backend, bindings);
                case 'M':
                    // X10 mouse report (terminals without SGR mouse): ESC[M Cb Cx Cy.
                    return parseMouseX10(backend, bindings);
                default:
                    // Any other final byte right after ESC[ ends the sequence (e.g. ESC[I focus-in).
                    return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
            }
        }

        // Sequence with parameters: collect atomically until the final terminator byte (0x40..0x7E)
        // or EOF. No total-length cap: alacritty/vte, crossterm and friends do not cap total length
        // either, since the Kitty keyboard protocol embeds associated text as colon-separated code
        // points inline in a single CSI-u sequence and that text has no principled upper bound.
        // Structural caps (max params, max subparams) can be added later if a specific need arises.
        StringBuilder sb = new StringBuilder();
        sb.append((char) c);
        int terminator = -1;

        while (true) {
            int next = backend.read(PEEK_TIMEOUT);
            if (next == -1 || next == -2) {
                break;
            }
            if (next >= 0x40 && next <= 0x7E) {
                terminator = next;
                break;
            }
            sb.append((char) next);
        }

        if (terminator == -1) {
            // Reached EOF without a terminator: discard the partial sequence.
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        return parseCSIWithParams(sb.toString(), terminator, backend, bindings);
    }

    private static Event parseCSIWithParams(String params, int terminator, Backend backend, Bindings bindings) throws IOException {
        // Bracketed paste start: ESC[200~
        if (terminator == '~' && "200".equals(params)) {
            return readPasteContent(backend);
        }

        // Kitty CSI u sequence: ESC [ <params> u
        if (terminator == 'u') {
            return parseKittyCsiU(params, bindings);
        }

        // Parse sequences like "1~" (Home), "4~" (End), "5~" (PgUp), etc.
        if (terminator == '~') {
            return parseVT(params, bindings);
        }

        // Parse sequences with modifiers like "1;5A" (Ctrl+Up) or "1;2P" (Shift+F1)
        if ((terminator >= 'A' && terminator <= 'Z') || terminator == 'E') {
            return parseModifiedKey(params, terminator, bindings);
        }

        return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
    }

    private static Event parseKittyCsiU(String params, Bindings bindings) {
        if (params.startsWith("?")) {
            // Kitty mode query response (e.g. CSI ? 5 u) - consumed, ignore
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        String[] fields = params.split(";", -1);
        String field1 = fields.length > 0 ? fields[0] : "";
        String field2 = fields.length > 1 ? fields[1] : "";
        String field3 = fields.length > 2 ? fields[2] : "";

        String[] keyParts = field1.split(":", -1);
        int unicodeKeyCode;
        try {
            unicodeKeyCode = Integer.parseInt(keyParts[0]);
        } catch (NumberFormatException e) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        OptionalInt shiftedKeyCode = OptionalInt.empty();
        if (keyParts.length > 1 && !keyParts[1].isEmpty()) {
            try {
                shiftedKeyCode = OptionalInt.of(Integer.parseInt(keyParts[1]));
            } catch (NumberFormatException ignored) {
            }
        }

        OptionalInt baseLayoutKeyCode = OptionalInt.empty();
        if (keyParts.length > 2 && !keyParts[2].isEmpty()) {
            try {
                baseLayoutKeyCode = OptionalInt.of(Integer.parseInt(keyParts[2]));
            } catch (NumberFormatException ignored) {
            }
        }

        ParsedModifiers pm = parseModifierAndKind(field2);

        String text = "";
        String textField = "";
        for (int i = 2; i < fields.length; i++) {
            if (!fields[i].isEmpty()) {
                textField = fields[i];
                break;
            }
        }
        if (!textField.isEmpty()) {
            String[] textParts = textField.split(":", -1);
            StringBuilder textSb = new StringBuilder();
            for (String cpStr : textParts) {
                if (!cpStr.isEmpty()) {
                    try {
                        int cp = Integer.parseInt(cpStr);
                        textSb.append(Character.toChars(cp));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            text = textSb.toString();
        }

        KeyCode code;
        int character = 0;
        int keyCodePoint = unicodeKeyCode;

        if (unicodeKeyCode == 0) {
            code = KeyCode.CHAR;
        } else if (unicodeKeyCode == 27) {
            code = KeyCode.ESCAPE;
        } else if (unicodeKeyCode == 13) {
            code = KeyCode.ENTER;
        } else if (unicodeKeyCode == 9) {
            code = KeyCode.TAB;
        } else if (unicodeKeyCode == 127) {
            code = KeyCode.BACKSPACE;
        } else {
            code = mapKittyFunctionalKey(unicodeKeyCode);
            if (code == null) {
                if (unicodeKeyCode >= 32) {
                    code = KeyCode.CHAR;
                    character = unicodeKeyCode;
                } else {
                    code = KeyCode.UNKNOWN;
                }
            }
        }

        boolean keypad = isKeypadKeyCode(code);
        KeyEventState state = KeyEventState.of(pm.capsLock, pm.numLock, keypad);

        // For keypad digits and operators (KP_0..KP_9, KP_ADD, KP_SUBTRACT, ...), set the character
        // field so text widgets insert the typed character via the usual codePoint()/string()
        // accessors. The KP_* KeyCode is preserved so bindings can still target the keypad key
        // distinctly from its main-row equivalent.
        if (character == 0) {
            character = keypadCharacter(code);
        }

        if (text.isEmpty()) {
            // Synthesize text only for plain typing. Any non-shift modifier (Ctrl, Alt, Super,
            // Hyper, Meta) means this is a shortcut; the terminal is the authority on associated
            // text for shortcuts, and if it did not provide any we must not fabricate it.
            if (code == KeyCode.CHAR && !pm.modifiers.hasNonShift()) {
                if (shiftedKeyCode.isPresent() && pm.modifiers.shift()) {
                    text = new String(Character.toChars(shiftedKeyCode.getAsInt()));
                    character = shiftedKeyCode.getAsInt();
                } else if (character != 0) {
                    text = new String(Character.toChars(character));
                }
            }
        } else if (code == KeyCode.CHAR && character == 0) {
            if (text.codePointCount(0, text.length()) == 1) {
                character = text.codePointAt(0);
            }
        }

        return new KeyEvent(
                code,
                pm.modifiers,
                character,
                bindings,
                pm.kind,
                state,
                keyCodePoint,
                shiftedKeyCode,
                baseLayoutKeyCode,
                text
        );
    }

    private static KeyCode mapKittyFunctionalKey(int code) {
        switch (code) {
            case 57358: return KeyCode.CAPS_LOCK;
            case 57359: return KeyCode.SCROLL_LOCK;
            case 57360: return KeyCode.NUM_LOCK;
            case 57361: return KeyCode.PRINT_SCREEN;
            case 57362: return KeyCode.PAUSE;
            case 57363: return KeyCode.MENU;
            case 57364: return KeyCode.F1;
            case 57365: return KeyCode.F2;
            case 57366: return KeyCode.F3;
            case 57367: return KeyCode.F4;
            case 57368: return KeyCode.F5;
            case 57369: return KeyCode.F6;
            case 57370: return KeyCode.F7;
            case 57371: return KeyCode.F8;
            case 57372: return KeyCode.F9;
            case 57373: return KeyCode.F10;
            case 57374: return KeyCode.F11;
            case 57375: return KeyCode.F12;
            case 57376: return KeyCode.F13;
            case 57377: return KeyCode.F14;
            case 57378: return KeyCode.F15;
            case 57379: return KeyCode.F16;
            case 57380: return KeyCode.F17;
            case 57381: return KeyCode.F18;
            case 57382: return KeyCode.F19;
            case 57383: return KeyCode.F20;
            case 57384: return KeyCode.F21;
            case 57385: return KeyCode.F22;
            case 57386: return KeyCode.F23;
            case 57387: return KeyCode.F24;
            case 57388: return KeyCode.F25;
            case 57389: return KeyCode.F26;
            case 57390: return KeyCode.F27;
            case 57391: return KeyCode.F28;
            case 57392: return KeyCode.F29;
            case 57393: return KeyCode.F30;
            case 57394: return KeyCode.F31;
            case 57395: return KeyCode.F32;
            case 57396: return KeyCode.F33;
            case 57397: return KeyCode.F34;
            case 57398: return KeyCode.F35;
            case 57399: return KeyCode.KP_0;
            case 57400: return KeyCode.KP_1;
            case 57401: return KeyCode.KP_2;
            case 57402: return KeyCode.KP_3;
            case 57403: return KeyCode.KP_4;
            case 57404: return KeyCode.KP_5;
            case 57405: return KeyCode.KP_6;
            case 57406: return KeyCode.KP_7;
            case 57407: return KeyCode.KP_8;
            case 57408: return KeyCode.KP_9;
            case 57409: return KeyCode.KP_DECIMAL;
            case 57410: return KeyCode.KP_DIVIDE;
            case 57411: return KeyCode.KP_MULTIPLY;
            case 57412: return KeyCode.KP_SUBTRACT;
            case 57413: return KeyCode.KP_ADD;
            case 57414: return KeyCode.KP_ENTER;
            case 57415: return KeyCode.KP_EQUAL;
            case 57416: return KeyCode.KP_SEPARATOR;
            case 57417: return KeyCode.KP_LEFT;
            case 57418: return KeyCode.KP_RIGHT;
            case 57419: return KeyCode.KP_UP;
            case 57420: return KeyCode.KP_DOWN;
            case 57421: return KeyCode.KP_PAGE_UP;
            case 57422: return KeyCode.KP_PAGE_DOWN;
            case 57423: return KeyCode.KP_HOME;
            case 57424: return KeyCode.KP_END;
            case 57425: return KeyCode.KP_INSERT;
            case 57426: return KeyCode.KP_DELETE;
            case 57427: return KeyCode.KP_BEGIN;
            case 57428: return KeyCode.MEDIA_PLAY;
            case 57429: return KeyCode.MEDIA_PAUSE;
            case 57430: return KeyCode.MEDIA_PLAY_PAUSE;
            case 57431: return KeyCode.MEDIA_REVERSE;
            case 57432: return KeyCode.MEDIA_STOP;
            case 57433: return KeyCode.MEDIA_FAST_FORWARD;
            case 57434: return KeyCode.MEDIA_REWIND;
            case 57435: return KeyCode.MEDIA_TRACK_NEXT;
            case 57436: return KeyCode.MEDIA_TRACK_PREVIOUS;
            case 57437: return KeyCode.MEDIA_RECORD;
            case 57438: return KeyCode.LOWER_VOLUME;
            case 57439: return KeyCode.RAISE_VOLUME;
            case 57440: return KeyCode.MUTE_VOLUME;
            case 57441: return KeyCode.LEFT_SHIFT;
            case 57442: return KeyCode.LEFT_CONTROL;
            case 57443: return KeyCode.LEFT_ALT;
            case 57444: return KeyCode.LEFT_SUPER;
            case 57445: return KeyCode.LEFT_HYPER;
            case 57446: return KeyCode.LEFT_META;
            case 57447: return KeyCode.RIGHT_SHIFT;
            case 57448: return KeyCode.RIGHT_CONTROL;
            case 57449: return KeyCode.RIGHT_ALT;
            case 57450: return KeyCode.RIGHT_SUPER;
            case 57451: return KeyCode.RIGHT_HYPER;
            case 57452: return KeyCode.RIGHT_META;
            case 57453: return KeyCode.ISO_LEVEL3_SHIFT;
            case 57454: return KeyCode.ISO_LEVEL5_SHIFT;
            default: return null;
        }
    }

    /**
     * Returns the character that a keypad digit or arithmetic key would type, or {@code 0} if the
     * given code is not such a key. Navigation keypad keys (KP_LEFT, KP_ENTER, ...) return {@code 0}
     * because they do not represent typed characters.
     */
    private static int keypadCharacter(KeyCode code) {
        if (code == null) {
            return 0;
        }
        switch (code) {
            case KP_0: return '0';
            case KP_1: return '1';
            case KP_2: return '2';
            case KP_3: return '3';
            case KP_4: return '4';
            case KP_5: return '5';
            case KP_6: return '6';
            case KP_7: return '7';
            case KP_8: return '8';
            case KP_9: return '9';
            case KP_ADD:       return '+';
            case KP_SUBTRACT:  return '-';
            case KP_MULTIPLY:  return '*';
            case KP_DIVIDE:    return '/';
            case KP_DECIMAL:   return '.';
            case KP_EQUAL:     return '=';
            case KP_SEPARATOR: return ',';
            default: return 0;
        }
    }

    private static boolean isKeypadKeyCode(KeyCode code) {
        if (code == null) {
            return false;
        }
        switch (code) {
            case KP_0:
            case KP_1:
            case KP_2:
            case KP_3:
            case KP_4:
            case KP_5:
            case KP_6:
            case KP_7:
            case KP_8:
            case KP_9:
            case KP_DECIMAL:
            case KP_DIVIDE:
            case KP_MULTIPLY:
            case KP_SUBTRACT:
            case KP_ADD:
            case KP_ENTER:
            case KP_EQUAL:
            case KP_SEPARATOR:
            case KP_LEFT:
            case KP_RIGHT:
            case KP_UP:
            case KP_DOWN:
            case KP_PAGE_UP:
            case KP_PAGE_DOWN:
            case KP_HOME:
            case KP_END:
            case KP_INSERT:
            case KP_DELETE:
            case KP_BEGIN:
                return true;
            default:
                return false;
        }
    }

    private static final class ParsedModifiers {
        final KeyModifiers modifiers;
        final KeyEventKind kind;
        final boolean capsLock;
        final boolean numLock;

        ParsedModifiers(KeyModifiers modifiers, KeyEventKind kind, boolean capsLock, boolean numLock) {
            this.modifiers = modifiers;
            this.kind = kind;
            this.capsLock = capsLock;
            this.numLock = numLock;
        }
    }

    private static ParsedModifiers parseModifierAndKind(String modString) {
        if (modString == null || modString.isEmpty()) {
            return new ParsedModifiers(KeyModifiers.NONE, KeyEventKind.PRESS, false, false);
        }
        String[] parts = modString.split(":", -1);
        int mod = 0;
        try {
            mod = Integer.parseInt(parts[0]) - 1;
        } catch (NumberFormatException ignored) {
        }

        boolean shift = (mod & 1) != 0;
        boolean alt = (mod & 2) != 0;
        boolean ctrl = (mod & 4) != 0;
        boolean superKey = (mod & 8) != 0;
        boolean hyper = (mod & 16) != 0;
        boolean meta = (mod & 32) != 0;
        boolean capsLock = (mod & 64) != 0;
        boolean numLock = (mod & 128) != 0;
        KeyModifiers modifiers = KeyModifiers.of(ctrl, alt, shift, superKey, hyper, meta);

        KeyEventKind kind = KeyEventKind.PRESS;
        if (parts.length > 1 && !parts[1].isEmpty()) {
            try {
                int eventType = Integer.parseInt(parts[1]);
                switch (eventType) {
                    case 1:
                        kind = KeyEventKind.PRESS;
                        break;
                    case 2:
                        kind = KeyEventKind.REPEAT;
                        break;
                    case 3:
                        kind = KeyEventKind.RELEASE;
                        break;
                    default:
                        kind = KeyEventKind.PRESS;
                        break;
                }
            } catch (NumberFormatException ignored) {
            }
        }

        return new ParsedModifiers(modifiers, kind, capsLock, numLock);
    }

    private static Event readPasteContent(Backend backend) throws IOException {
        StringBuilder sb = new StringBuilder();
        // 批量快路径：绕开 JLine 非阻塞层的逐字节 wait/notify 交接（实测每字节 ~6µs，
        // 4MB 粘贴要 25s+，期间用户按键全堵在后面——真机实报「贴大日志卡死」），
        // 反射穿透到 pty 原始 InputStream 做整块 read。任何一步反射失败 → 逐字符兜底。
        InputStream rawIn = rawBulkStream(backend);
        if (rawIn != null) {
            return readPasteRawBulk(backend, rawIn, sb);
        }
        return readPasteCharByChar(backend, sb);
    }

    /**
     * 原始流批量读取粘贴体。
     *
     * <p><b>安全性论证</b>（为什么可以直接读原始流）：进入本方法前，解析器刚通过
     * {@code backend.read/peek} 消费完粘贴<b>起始标记</b> ESC[200~——非阻塞层的单字节
     * 交接（threadIsReading / 残留字节 b）此刻已完结，读线程停在 {@code needToRead=false}
     * 的 wait 上，只有下一次 read 请求才会唤醒它。本方法<b>只</b>通过非阻塞层取第一个
     * 字节（顺带消费任何 peek 残留），此后再不碰它，读线程保持停机 → 原始流无并发读者。
     *
     * <p><b>静默预算</b>：用 {@code available()} 探测而非阻塞读——原始流 read 无超时，
     * 阻塞等数据会把用户在终止符丢失场景下的按键吞进粘贴体；available==0 时睡
     * {@link #PEEK_TIMEOUT} 再查，累计静默超过 {@link #PASTE_QUIET_BUDGET_NANOS} 放弃，
     * 与逐字符路径同语义。UTF-8 自行解码（跨块多字节序列经 CharsetDecoder carry）。
     */
    private static Event readPasteRawBulk(Backend backend, InputStream rawIn, StringBuilder sb)
            throws IOException {
        PasteBodyScanner scanner = new PasteBodyScanner();
        // 第一个字节走非阻塞层：消费 peek 残留、确认读线程交接完成（见方法注释）。
        long quietStartNanos = -1L;
        int first;
        while (true) {
            first = backend.read(PEEK_TIMEOUT);
            if (first == -2) {
                if (quietStartNanos < 0) quietStartNanos = System.nanoTime();
                else if (System.nanoTime() - quietStartNanos >= PASTE_QUIET_BUDGET_NANOS) {
                    return new PasteEvent(sb.toString());   // 起始标记后一个字节都没来：按空粘贴收尾
                }
                continue;
            }
            if (first < 0) return new PasteEvent(sb.toString());   // EOF
            break;
        }
        if (scanner.accept(first, sb)) return new PasteEvent(sb.toString());

        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        byte[] inBuf = new byte[8192];
        java.nio.ByteBuffer carry = java.nio.ByteBuffer.allocate(8192);
        carry.limit(0);   // 起始为空：position=0, limit=0 表示无残留字节
        quietStartNanos = -1L;
        while (true) {
            int avail = rawIn.available();
            if (avail <= 0) {
                if (quietStartNanos < 0) quietStartNanos = System.nanoTime();
                else if (System.nanoTime() - quietStartNanos >= PASTE_QUIET_BUDGET_NANOS) break;
                sleepQuietly(RAW_POLL_MS);
                continue;
            }
            int n = rawIn.read(inBuf, 0, Math.min(avail, inBuf.length));
            if (n < 0) break;   // EOF
            if (n == 0) continue;
            quietStartNanos = -1L;
            // 拼进 carry（可能带着上一块的多字节残尾）再整体解码
            java.nio.ByteBuffer block = java.nio.ByteBuffer.allocate(carry.remaining() + n);
            block.put(carry);
            block.put(inBuf, 0, n);
            block.flip();
            java.nio.CharBuffer out = java.nio.CharBuffer.allocate(block.remaining() + 8);
            decoder.decode(block, out, false);
            block.compact();
            // compact 后 block 里 [0, position) 是未解码完的残尾——拷回 carry 供下一块
            carry.clear();
            int rem = block.position();
            for (int i = 0; i < rem; i++) carry.put(block.get(i));
            carry.flip();
            out.flip();
            boolean ended = false;
            while (out.hasRemaining()) {
                if (scanner.accept(out.get(), sb)) {
                    ended = true;
                    break;
                }
            }
            if (ended) {
                // 终止符之后同块里的字节不属于本条粘贴——那是下一条粘贴的开头
                // （多文件拖拽背靠背），入库回灌，绝不丢弃。carry 已 flip，恰好是
                // 本块未解码完的残尾字节。
                stashOvershoot(out, carry);
                break;
            }
        }
        return new PasteEvent(sb.toString());
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 反射穿透 {@code backend.reader}（NonBlockingReader）→ {@code input}
     * （NonBlockingInputStream）→ {@code in}（pty 原始 InputStream）。字段名/类型任何
     * 一步对不上（非 JLine 后端、jline 升级改名）→ null → 逐字符兜底，
     * <b>绝不因反射失败影响功能</b>。每次粘贴只反射一次（不是每字节），开销可忽略。
     * 字段名对照 jline 3.25.1：JLineBackend.reader / NonBlocking$NonBlockingInputStreamReader.input /
     * NonBlockingInputStreamImpl.in。
     */
    private static InputStream rawBulkStream(Backend backend) {
        try {
            Object reader = digField(backend, "reader");
            if (!(reader instanceof org.jline.utils.NonBlockingReader)) return null;
            Object input = digField(reader, "input");
            if (!(input instanceof org.jline.utils.NonBlockingInputStream)) return null;
            Object in = digField(input, "in");
            if (!(in instanceof InputStream raw)) return null;
            return raw;
        } catch (RuntimeException | ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** 沿类层次找指定名实例字段并取值；找不到/不可读返回 null。 */
    private static Object digField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // 沿父类链继续
            }
        }
        return null;
    }

    /** 逐字符慢路径（backend 不暴露 NonBlockingReader 时的兜底），语义与快路径一致。 */
    private static Event readPasteCharByChar(Backend backend, StringBuilder sb) throws IOException {
        PasteBodyScanner scanner = new PasteBodyScanner();
        long quietStartNanos = -1L;
        while (true) {
            int c = backend.read(PEEK_TIMEOUT);
            if (c == -2) {
                if (quietStartNanos < 0) {
                    quietStartNanos = System.nanoTime();
                } else if (System.nanoTime() - quietStartNanos >= PASTE_QUIET_BUDGET_NANOS) {
                    break;   // 静默满预算：对端真的不再来了，按部分内容收尾
                }
                continue;    // 预算内：继续等下一块
            }
            quietStartNanos = -1L;
            if (c < 0) break;   // EOF：流已死，等下去没有意义
            if (scanner.accept(c, sb)) break;   // ESC[201~ matched — end of paste
        }
        return new PasteEvent(sb.toString());
    }

    /**
     * 粘贴终止符 {@code ESC[201~} 的流式状态机：批量与逐字符两条读取路径共用，
     * 终止符可跨块边界匹配；匹配失败把已吃下的前缀<b>原样回滚</b>为字面量（日志里的
     * ANSI 颜色码如 ESC[31m 必须按原文保留），再从起点重新判定当前字符。
     */
    static final class PasteBodyScanner {
        private static final String END_MARK = "\u001b[201~";
        private int matched;

        /**
         * 处理一个字符；返回 true 表示终止符完整匹配（此字符及之前的前缀都不进正文）。
         * 字面量字符（含回滚）写入 {@code out}。
         */
        boolean accept(int c, StringBuilder out) {
            if (matched == 0) {
                if (c == ESC) {
                    matched = 1;
                } else {
                    out.appendCodePoint(c);
                }
                return false;
            }
            if (c == END_MARK.charAt(matched)) {
                matched++;
                if (matched == END_MARK.length()) {
                    matched = 0;
                    return true;
                }
                return false;
            }
            // 前缀失配：已匹配部分回滚为字面量，当前字符从起点重判（c 自身若是 ESC
            // 会开启新匹配——如 ESC ESC [201~ 中第二个 ESC）。
            for (int i = 0; i < matched; i++) {
                out.appendCodePoint(END_MARK.charAt(i));
            }
            matched = 0;
            return accept(c, out);   // 深度恒为 1：matched 已清零
        }
    }


    private static Event parseVT(String params, Bindings bindings) {
        String[] parts = params.split(";", -1);
        int code;
        try {
            code = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        // xterm modifyOtherKeys: CSI 27 ; mod ; key ~ (e.g. ESC[27;2;13~ = Shift+Enter). The real
        // key is the third parameter; normalize to the CSI-u form (key;mod) and reuse its mapping so
        // Enter/Tab/Backspace/Escape, printable characters and functional keys decode consistently.
        if (code == 27) {
            if (parts.length < 3 || parts[2].isEmpty()) {
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
            }
            return parseKittyCsiU(parts[2] + ";" + parts[1], bindings);
        }

        ParsedModifiers pm = parts.length > 1
                ? parseModifierAndKind(parts[1])
                : new ParsedModifiers(KeyModifiers.NONE, KeyEventKind.PRESS, false, false);

        KeyCode keyCode;
        switch (code) {
            case 1:
            case 7:
                keyCode = KeyCode.HOME;
                break;
            case 2:
                keyCode = KeyCode.INSERT;
                break;
            case 3:
                keyCode = KeyCode.DELETE;
                break;
            case 4:
            case 8:
                keyCode = KeyCode.END;
                break;
            case 5:
                keyCode = KeyCode.PAGE_UP;
                break;
            case 6:
                keyCode = KeyCode.PAGE_DOWN;
                break;
            case 11:
                keyCode = KeyCode.F1;
                break;
            case 12:
                keyCode = KeyCode.F2;
                break;
            case 13:
                keyCode = KeyCode.F3;
                break;
            case 14:
                keyCode = KeyCode.F4;
                break;
            case 15:
                keyCode = KeyCode.F5;
                break;
            case 17:
                keyCode = KeyCode.F6;
                break;
            case 18:
                keyCode = KeyCode.F7;
                break;
            case 19:
                keyCode = KeyCode.F8;
                break;
            case 20:
                keyCode = KeyCode.F9;
                break;
            case 21:
                keyCode = KeyCode.F10;
                break;
            case 23:
                keyCode = KeyCode.F11;
                break;
            case 24:
                keyCode = KeyCode.F12;
                break;
            case 29:
                keyCode = KeyCode.MENU;
                break;
            case 57427:
                keyCode = KeyCode.KP_BEGIN;
                break;
            default:
                keyCode = KeyCode.UNKNOWN;
                break;
        }

        boolean keypad = isKeypadKeyCode(keyCode);
        KeyEventState state = KeyEventState.of(pm.capsLock, pm.numLock, keypad);
        return new KeyEvent(
                keyCode,
                pm.modifiers,
                0,
                bindings,
                pm.kind,
                state,
                0,
                OptionalInt.empty(),
                OptionalInt.empty(),
                ""
        );
    }

    private static Event parseModifiedKey(String params, int terminator, Bindings bindings) {
        String[] parts = params.split(";", -1);
        ParsedModifiers pm = parts.length > 1
                ? parseModifierAndKind(parts[1])
                : new ParsedModifiers(KeyModifiers.NONE, KeyEventKind.PRESS, false, false);

        KeyCode code;
        KeyModifiers mods = pm.modifiers;

        switch (terminator) {
            case 'A':
                code = KeyCode.UP;
                break;
            case 'B':
                code = KeyCode.DOWN;
                break;
            case 'C':
                code = KeyCode.RIGHT;
                break;
            case 'D':
                code = KeyCode.LEFT;
                break;
            case 'H':
                code = KeyCode.HOME;
                break;
            case 'F':
                code = KeyCode.END;
                break;
            case 'E':
                code = KeyCode.KP_BEGIN;
                break;
            case 'P':
                code = KeyCode.F1;
                break;
            case 'Q':
                code = KeyCode.F2;
                break;
            case 'R':
                code = KeyCode.F3;
                break;
            case 'S':
                code = KeyCode.F4;
                break;
            case 'Z':
                code = KeyCode.TAB;
                mods = mods.shift()
                        ? mods
                        : KeyModifiers.of(mods.ctrl(), mods.alt(), true, mods.superKey(), mods.hyper(), mods.meta());
                break;
            default:
                code = KeyCode.UNKNOWN;
                break;
        }

        boolean keypad = isKeypadKeyCode(code);
        KeyEventState state = KeyEventState.of(pm.capsLock, pm.numLock, keypad);
        return new KeyEvent(
                code,
                mods,
                0,
                bindings,
                pm.kind,
                state,
                0,
                OptionalInt.empty(),
                OptionalInt.empty(),
                ""
        );
    }

    private static KeyCode cursorOrFunctionKey(int finalByte) {
        switch (finalByte) {
            case 'A':
                return KeyCode.UP;
            case 'B':
                return KeyCode.DOWN;
            case 'C':
                return KeyCode.RIGHT;
            case 'D':
                return KeyCode.LEFT;
            case 'H':
                return KeyCode.HOME;
            case 'F':
                return KeyCode.END;
            case 'E':
                return KeyCode.KP_BEGIN;
            case 'P':
                return KeyCode.F1;
            case 'Q':
                return KeyCode.F2;
            case 'R':
                return KeyCode.F3;
            case 'S':
                return KeyCode.F4;
            default:
                return KeyCode.UNKNOWN;
        }
    }

    private static Event parseLinuxConsoleFunctionKey(Backend backend, Bindings bindings) throws IOException {
        // The Linux console sends F1-F5 as the double-bracket form ESC [ [ A to ESC [ [ E.
        switch (backend.read(PEEK_TIMEOUT)) {
            case 'A':
                return KeyEvent.ofKey(KeyCode.F1, bindings);
            case 'B':
                return KeyEvent.ofKey(KeyCode.F2, bindings);
            case 'C':
                return KeyEvent.ofKey(KeyCode.F3, bindings);
            case 'D':
                return KeyEvent.ofKey(KeyCode.F4, bindings);
            case 'E':
                return KeyEvent.ofKey(KeyCode.F5, bindings);
            default:
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }
    }

    private static Event parseSS3(Backend backend, Bindings bindings) throws IOException {
        // SS3 sequences (typically function keys on some terminals). Konsole puts the modifier
        // between SS3 and the final byte: ESC O 2 P is Shift+F1, as ESC [ 1 ; 2 P is elsewhere.
        StringBuilder modifier = new StringBuilder();
        int c = backend.read(PEEK_TIMEOUT);
        while (c >= '0' && c <= '9') {
            modifier.append((char) c);
            c = backend.read(PEEK_TIMEOUT);
        }
        if (c == -2 || c == -1) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        KeyModifiers mods = modifier.length() > 0
                ? parseModifierAndKind(modifier.toString()).modifiers
                : KeyModifiers.NONE;
        return KeyEvent.ofKey(cursorOrFunctionKey(c), mods, bindings);
    }

    private static Event parseMouseSGR(Backend backend, Bindings bindings) throws IOException {
        // SGR mouse format: ESC [ < Cb ; Cx ; Cy M/m
        // where Cb is button code, Cx is column, Cy is row
        // M = press/drag, m = release

        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = backend.read(PEEK_TIMEOUT)) != -2 && c != -1) {
            if (c == 'M' || c == 'm') {
                return parseMouseParams(sb.toString(), c == 'm', bindings);
            }
            sb.append((char) c);
        }

        // Return UNKNOWN for incomplete mouse sequence
        return KeyEvent.ofKey(KeyCode.UNKNOWN);
    }

    private static Event parseMouseX10(Backend backend, Bindings bindings) throws IOException {
        // X10 mouse format: ESC [ M Cb Cx Cy, three bytes offset by 32, with 1-based coordinates.
        // Terminals without SGR mouse support report this way, such as GNU screen 4 and the Linux console.
        int cb = backend.read(PEEK_TIMEOUT);
        int cx = cb < 0 ? cb : backend.read(PEEK_TIMEOUT);
        int cy = cx < 0 ? cx : backend.read(PEEK_TIMEOUT);
        // Coordinates start at 33. A value above 255 is not a single byte, such as the U+FFFD that a
        // UTF-8 reader makes of a byte above 127: the report has no usable position, so drop it whole.
        if (cb < 32 || cb > 255 || cx < 33 || cx > 255 || cy < 33 || cy > 255) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        int buttonCode = cb - 32;
        // X10 reports every release as button 3, without saying which button was released
        boolean isRelease = (buttonCode & ~(4 | 8 | 16)) == 3;
        return mouseEvent(buttonCode, cx - 33, cy - 33, isRelease, bindings);
    }

    private static Event parseMouseParams(String params, boolean isRelease, Bindings bindings) {
        String[] parts = params.split(";", -1);
        if (parts.length < 3) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN);
        }

        int buttonCode;
        int x;
        int y;
        try {
            buttonCode = Integer.parseInt(parts[0]);
            x = Integer.parseInt(parts[1]) - 1; // Convert to 0-indexed
            y = Integer.parseInt(parts[2]) - 1;
        } catch (NumberFormatException e) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN);
        }

        return mouseEvent(buttonCode, x, y, isRelease, bindings);
    }

    private static MouseEvent mouseEvent(int buttonCode, int x, int y, boolean isRelease, Bindings bindings) {
        // Parse modifiers from button code
        boolean shift = (buttonCode & 4) != 0;
        boolean alt = (buttonCode & 8) != 0;
        boolean ctrl = (buttonCode & 16) != 0;
        KeyModifiers mods = KeyModifiers.of(ctrl, alt, shift);

        // Clear modifier bits to get actual button
        int button = buttonCode & ~(4 | 8 | 16);

        // Determine event kind and button
        if (button >= 64 && button <= 67) {
            // Scroll wheel: 64=up, 65=down, 66=left, 67=right
            MouseEventKind kind;
            switch (button) {
                case 64:
                    kind = MouseEventKind.SCROLL_UP;
                    break;
                case 65:
                    kind = MouseEventKind.SCROLL_DOWN;
                    break;
                case 66:
                    kind = MouseEventKind.SCROLL_LEFT;
                    break;
                default:
                    kind = MouseEventKind.SCROLL_RIGHT;
                    break;
            }
            return new MouseEvent(kind, MouseButton.NONE, x, y, mods, bindings);
        }

        boolean motion = (button & 32) != 0;
        button = button & ~32;

        MouseButton mouseButton;
        switch (button) {
            case 0:
                mouseButton = MouseButton.LEFT;
                break;
            case 1:
                mouseButton = MouseButton.MIDDLE;
                break;
            case 2:
                mouseButton = MouseButton.RIGHT;
                break;
            default:
                mouseButton = MouseButton.NONE;
                break;
        }

        MouseEventKind kind;
        if (isRelease) {
            kind = MouseEventKind.RELEASE;
        } else if (motion && mouseButton == MouseButton.NONE) {
            // Motion with no button held is a hover (any-event tracking, DECSET 1003)
            kind = MouseEventKind.MOVE;
        } else if (motion) {
            // Motion with a button held is a drag
            kind = MouseEventKind.DRAG;
        } else {
            kind = MouseEventKind.PRESS;
        }

        return new MouseEvent(kind, mouseButton, x, y, mods, bindings);
    }
}

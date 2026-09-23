/*
 * Copyright TamboUI Contributors
 * SPDX-License-Identifier: MIT
 */
package dev.tamboui.tui.event;

import java.io.IOException;
import java.io.InputStream;

import dev.tamboui.terminal.Backend;
import dev.tamboui.tui.bindings.BindingSets;
import dev.tamboui.tui.bindings.Bindings;

/**
 * Parses raw terminal input into typed {@link Event} objects.
 * <p>
 * Handles escape sequences for arrow keys, function keys, navigation keys,
 * and mouse events (SGR extended mode).
 *
 * <p><b>springai-tamboui-inline-patch shadow 类</b>（与 {@code InlineTuiRunner} 同机制：
 * code-tui 的 Class-Path 顺序保证本 jar 先于 tamboui-tui 加载）。与上游 0.4.0 的唯一
 * 差异在 {@link #readPasteContent}：上游对粘贴内容逐字符 {@code read(50ms)}，超时立刻
 * 放弃——大粘贴（几百 KB 日志）时终端分块灌 pty、块间间隙一旦超过 50ms，粘贴事件被
 * 提前掐断，剩余字节回到普通按键解析：日志里的 {@code \n} 变 Enter、在 code-tui 里
 * 触发「贴入即分批自动发送」。shadow 把块间等待放宽为<b>累计静默 500ms</b> 才放弃
 * （人手敲不出 ESC[200~ 起始标记，无副作用）；EOF 仍立即放弃。行为契约见
 * {@code EventParserPasteTest}。库升级 0.4.x 以上时须重对本类做 diff。
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

    private EventParser() {
    }

    /**
     * Reads and parses the next event from the backend using the default bindings.
     *
     * @param backend the terminal backend
     * @param timeout timeout in milliseconds for the initial read
     * @return the parsed event, or null if no event was available
     * @throws IOException if an I/O error occurs
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
     * @throws IOException if an I/O error occurs
     */
    public static Event readEvent(Backend backend, int timeout, Bindings bindings) throws IOException {
        int c = backend.read(timeout);

        if (c == -2) {
            // Timeout - no input available
            return null;
        }

        if (c == -1) {
            // EOF
            return null;
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

    private static Event parseControlChar(int c, Bindings bindings) {
        switch (c) {
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
            default:
                if (c >= 1 && c <= 26) {
                    char letter = (char) ('a' + c - 1);
                    return KeyEvent.ofChar(letter, KeyModifiers.CTRL, bindings);
                }
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }
    }

    private static Event parseEscapeSequence(Backend backend, Bindings bindings) throws IOException {
        int next = backend.peek(PEEK_TIMEOUT);

        if (next == -2 || next == -1) {
            // Standalone ESC key
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

        // Alt+key
        backend.read(PEEK_TIMEOUT); // consume the character
        if (next >= 32 && next != 127) {
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

        // Arrow keys and simple sequences
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
            case 'Z':
                // Shift+Tab (backtab) - ESC[Z
                return KeyEvent.ofKey(KeyCode.TAB, KeyModifiers.SHIFT, bindings);
            default:
                return parseExtendedCSI(c, backend, bindings);
        }
    }

    private static Event parseExtendedCSI(int first, Backend backend, Bindings bindings) throws IOException {
        // Parse numeric parameter(s)
        StringBuilder sb = new StringBuilder();
        sb.append((char) first);

        int c;
        while ((c = backend.read(PEEK_TIMEOUT)) != -2 && c != -1) {
            if (c >= '0' && c <= '9' || c == ';') {
                sb.append((char) c);
            } else {
                // End of sequence
                return parseCSIWithParams(sb.toString(), c, backend, bindings);
            }
        }

        return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
    }

    private static Event parseCSIWithParams(String params, int terminator, Backend backend, Bindings bindings) throws IOException {
        // Bracketed paste start: ESC[200~
        if (terminator == '~' && "200".equals(params)) {
            return readPasteContent(backend, bindings);
        }

        // Parse sequences like "1~" (Home), "4~" (End), "5~" (PgUp), etc.
        if (terminator == '~') {
            return parseVT(params, bindings);
        }

        // Parse sequences with modifiers like "1;5A" (Ctrl+Up)
        if (terminator >= 'A' && terminator <= 'Z') {
            return parseModifiedArrow(params, terminator, bindings);
        }

        return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
    }

    private static Event readPasteContent(Backend backend, Bindings bindings) throws IOException {
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
            if (ended) break;
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
        String[] parts = params.split(";");
        int code;
        try {
            code = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        KeyModifiers mods = parts.length > 1 ? parseModifierCode(parts[1]) : KeyModifiers.NONE;

        switch (code) {
            case 1:
                return KeyEvent.ofKey(KeyCode.HOME, mods, bindings);
            case 2:
                return KeyEvent.ofKey(KeyCode.INSERT, mods, bindings);
            case 3:
                return KeyEvent.ofKey(KeyCode.DELETE, mods, bindings);
            case 4:
                return KeyEvent.ofKey(KeyCode.END, mods, bindings);
            case 5:
                return KeyEvent.ofKey(KeyCode.PAGE_UP, mods, bindings);
            case 6:
                return KeyEvent.ofKey(KeyCode.PAGE_DOWN, mods, bindings);
            case 11:
                return KeyEvent.ofKey(KeyCode.F1, mods, bindings);
            case 12:
                return KeyEvent.ofKey(KeyCode.F2, mods, bindings);
            case 13:
                return KeyEvent.ofKey(KeyCode.F3, mods, bindings);
            case 14:
                return KeyEvent.ofKey(KeyCode.F4, mods, bindings);
            case 15:
                return KeyEvent.ofKey(KeyCode.F5, mods, bindings);
            case 17:
                return KeyEvent.ofKey(KeyCode.F6, mods, bindings);
            case 18:
                return KeyEvent.ofKey(KeyCode.F7, mods, bindings);
            case 19:
                return KeyEvent.ofKey(KeyCode.F8, mods, bindings);
            case 20:
                return KeyEvent.ofKey(KeyCode.F9, mods, bindings);
            case 21:
                return KeyEvent.ofKey(KeyCode.F10, mods, bindings);
            case 23:
                return KeyEvent.ofKey(KeyCode.F11, mods, bindings);
            case 24:
                return KeyEvent.ofKey(KeyCode.F12, mods, bindings);
            default:
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }
    }

    private static Event parseModifiedArrow(String params, int terminator, Bindings bindings) {
        String[] parts = params.split(";");
        KeyModifiers mods = parts.length > 1 ? parseModifierCode(parts[1]) : KeyModifiers.NONE;

        KeyCode code;
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
            default:
                code = KeyCode.UNKNOWN;
                break;
        }

        return KeyEvent.ofKey(code, mods, bindings);
    }

    private static KeyModifiers parseModifierCode(String code) {
        int mod;
        try {
            mod = Integer.parseInt(code);
        } catch (NumberFormatException e) {
            return KeyModifiers.NONE;
        }

        // Modifier encoding: 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0)
        mod = mod - 1;
        boolean shift = (mod & 1) != 0;
        boolean alt = (mod & 2) != 0;
        boolean ctrl = (mod & 4) != 0;

        return KeyModifiers.of(ctrl, alt, shift);
    }

    private static Event parseSS3(Backend backend, Bindings bindings) throws IOException {
        int c = backend.read(PEEK_TIMEOUT);
        if (c == -2 || c == -1) {
            return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }

        // SS3 sequences (typically function keys on some terminals)
        switch (c) {
            case 'P':
                return KeyEvent.ofKey(KeyCode.F1, bindings);
            case 'Q':
                return KeyEvent.ofKey(KeyCode.F2, bindings);
            case 'R':
                return KeyEvent.ofKey(KeyCode.F3, bindings);
            case 'S':
                return KeyEvent.ofKey(KeyCode.F4, bindings);
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
            default:
                return KeyEvent.ofKey(KeyCode.UNKNOWN, bindings);
        }
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

    private static Event parseMouseParams(String params, boolean isRelease, Bindings bindings) {
        String[] parts = params.split(";");
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

        // Parse modifiers from button code
        boolean shift = (buttonCode & 4) != 0;
        boolean alt = (buttonCode & 8) != 0;
        boolean ctrl = (buttonCode & 16) != 0;
        KeyModifiers mods = KeyModifiers.of(ctrl, alt, shift);

        // Clear modifier bits to get actual button
        int button = buttonCode & ~(4 | 8 | 16);

        // Determine event kind and button
        if (button >= 64 && button <= 65) {
            // Scroll wheel
            MouseEventKind kind = (button == 64) ? MouseEventKind.SCROLL_UP : MouseEventKind.SCROLL_DOWN;
            return new MouseEvent(kind, MouseButton.NONE, x, y, mods, bindings);
        }

        boolean isDrag = (button & 32) != 0;
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
        } else if (isDrag) {
            kind = MouseEventKind.DRAG;
        } else if (mouseButton == MouseButton.NONE) {
            kind = MouseEventKind.MOVE;
        } else {
            kind = MouseEventKind.PRESS;
        }

        return new MouseEvent(kind, mouseButton, x, y, mods, bindings);
    }
}

import dev.tamboui.buffer.Buffer;
import dev.tamboui.inline.InlineDisplay;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.text.Text;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terminal.app 崩溃诱因定位矩阵（2026-09-30 根因调查配套）。
 *
 * <p>背景：code-tui「输出时打字卡死/终端崩溃」已定案为 Apple Terminal.app (2.15/470.2,
 * macOS 26.5.2) 主线程 SIGSEGV（~/Library/Logs/DiagnosticReports/Terminal-*.ips）。
 * 但 Claude Code 同场景不崩——说明它的字节模式不踩 Terminal 的崩溃路径。本工具用
 * <b>真实的 InlineDisplay</b>（code-tui 实际渲染层）+ <b>带回显的输入框</b>复现
 * 「界面在输出、用户在打字」的完整模式，找出哪一种触发崩溃。
 *
 * <p>所有带输入框的场景：底部显示圆角输入框，直接打字即回显（支持中文/退格），
 * Enter 清空并计数「已发送」，Ctrl+C 安全退出。光标落在输入行内（与 code-tui 一致，
 * 触发 IME 光标带修复路径）。
 *
 * <p>用法：java -cp &lt;classpath&gt; ScenarioStress &lt;场景&gt; &lt;秒&gt;
 * <pre>
 *   println  S1  InlineDisplay.println 连续打印（code-tui 流式：中部 ESC[1L 插行+live 区重申）
 *   ink      S2  整帧擦除重写式打印（Claude Code/Ink 式：ESC[2K 逐行擦写，无插行）
 *   preview  S3  预览行出现/消失（高度 9↔10，ESC[1L/ESC[1M 行增删）+ 内容变化
 *   band     S4  光标行 ±1 整行无 EL 覆写（IME 光标带修复模式，~10Hz）
 *   anim     S5  整帧高频变化（动画/spinner 式，~15Hz）
 *   flood    S6  裸文本洪峰（无输入框，吞吐基线；打字可选）
 * </pre>
 */
public final class ScenarioStress {

    /** 输入框回显状态（stdin 线程写，渲染线程读）。 */
    static volatile String input = "";
    static final AtomicLong sent = new AtomicLong();
    static final AtomicBoolean stop = new AtomicBoolean();
    static volatile long inputVersion;   // 必须 volatile：stdin 线程写、渲染线程读（否则回显帧永不触发）
    static volatile long inputVersionSeen;

    static java.io.PrintWriter dbg;

    static void log(String fmt, Object... args) {
        java.io.PrintWriter w = dbg;
        if (w != null) {
            w.printf("%d [%s] %s%n", System.currentTimeMillis(),
                    Thread.currentThread().getName(), String.format(fmt, args));
            w.flush();
        }
    }

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "println";
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 90;
        String logPath = System.getenv("CODETUI_STRESS_LOG");
        if (logPath != null) {
            try { dbg = new java.io.PrintWriter(new java.io.FileWriter(logPath)); } catch (Exception ignored) { }
        }
        log("scenario=%s seconds=%d tty=%b", scenario, seconds, System.console() != null);

        int rc = 0;
        switch (scenario) {
            case "flood" -> runFlood(seconds);
            case "println" -> runDisplayLoop(seconds, Mode.PRINTLN);
            case "preview" -> runDisplayLoop(seconds, Mode.PREVIEW);
            case "band" -> runDisplayLoop(seconds, Mode.BAND);
            case "anim" -> runDisplayLoop(seconds, Mode.ANIM);
            case "ink" -> runInk(seconds);
            default -> { System.err.println("未知场景: " + scenario); rc = 2; }
        }
        log("main done, exiting");
        System.out.print("\033[0m\r\n[scenario " + scenario + " done]\r\n");
        System.out.flush();
        if (dbg != null) dbg.close();
        if (rc != 0) System.exit(rc);
    }

    enum Mode { PRINTLN, PREVIEW, BAND, ANIM }

    // ── stdin：回显输入框——走 backend.read()（JLine 非阻塞读，库正式路径；
    //    直读 System.in 在真 tty 下会被 JLine 的读泵饿死，打字与 Ctrl+C 全部失效） ──
    static void startInputReader(Backend b) {
        Thread t = new Thread(() -> {
            log("input thread start (backend.read path)");
            try {
                while (!stop.get()) {
                    int c = b.read(50);                 // -2=超时, -1=EOF, 否则码点
                    if (c == -2) continue;
                    log("recv cp=%d", c);
                    if (c < 0) { log("input EOF"); return; }
                    if (c == 3) { stop.set(true); return; }          // Ctrl+C
                    if (c == 127 || c == 8) {                        // 退格删一个码点
                        if (!input.isEmpty()) {
                            input = input.substring(0, input.offsetByCodePoints(input.length(), -1));
                        }
                    } else if (c == 13 || c == 10) {                 // Enter：清空+计数
                        sent.incrementAndGet(); input = "";
                    } else if (c >= 32) {
                        String add = new String(Character.toChars(c));
                        input = (input + add).substring(0, Math.min(48, input.length() + add.length()));
                    }
                    inputVersion++;
                }
            } catch (Exception e) { log("input thread died: %s", e); }
            log("input thread exit");
        }, "stdin-echo");
        t.setDaemon(true);
        t.start();
    }

    // ── 场景 ─────────────────────────────────────────────────────────

    /** S6：裸洪峰（无 ANSI、无输入框；打字可选）。 */
    static void runFlood(int seconds) throws Exception {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long n = 0;
        while (System.nanoTime() < end && !stop.get()) {
            System.out.print("flood " + n++ + " xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\r\n");
            if ((n & 0xFF) == 0) Thread.sleep(1);
        }
    }

    /** 真实 InlineDisplay 驱动的四类模式（println/preview/band/anim 共用骨架）。 */
    static void runDisplayLoop(int seconds, Mode mode) throws Exception {
        currentMode = mode;
        try (Backend b = BackendFactory.create()) {
            b.enableRawMode();
            log("backend ready, raw on");
            startInputReader(b);
            InlineDisplay d = InlineDisplay.withBackend(6, b);
            d.render((a, buf) -> paintLive(buf, "", mode), 6, 4, 1);
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            long n = 0;
            String[] spin = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
            while (System.nanoTime() < end && !stop.get()) {
                switch (mode) {
                    case PRINTLN -> {                                   // 持续流式行（含 live 重申）
                        d.println("stream " + n++ + " 模型输出内容，中文与 punctuation！`code`");
                        Thread.sleep(80);
                    }
                    case PREVIEW -> {                                   // 预览行起落 + 残行变化
                        boolean with = (n / 4) % 2 == 0;
                        renderIfChanged(d, with ? 7 : 6, with ? "preview 残行 " + n + " 正在生成…" : "");
                        n++;
                        Thread.sleep(150);
                    }
                    case BAND -> { renderIfChanged(d, 6, "band " + n++ + " 光标带重申"); Thread.sleep(100); }
                    case ANIM  -> { renderIfChanged(d, 6, spin[(int) (n % spin.length)] + " thinking " + n); n++; Thread.sleep(66); }
                }
                // 输入变化即补一帧（= 真实按键路径：每键一帧 diff + 光标带回申）
                if (inputVersionSeen != inputVersion) renderIfChanged(d, mode == Mode.PREVIEW && ((n / 4) % 2 == 0) ? 7 : 6, tailFor(mode, n, spin));
            }
            d.render((a, buf) -> paintLive(buf, "", mode), 6, 4, 1);    // 收尾归位
            log("loop end, closing backend");
        }
        log("backend closed");
    }

    static String tailFor(Mode m, long n, String[] spin) {
        return switch (m) {
            case PREVIEW -> ((n / 4) % 2 == 0) ? "preview 残行 " + n + " 正在生成…" : "";
            case BAND -> "band " + n + " 光标带重申";
            case ANIM -> spin[(int) (n % spin.length)] + " thinking " + n;
            default -> "";
        };
    }

    static void renderIfChanged(InlineDisplay d, int h, String tail) {
        Mode m = currentMode;
        int inputRow = tail.isEmpty() ? 1 : 2;    // [预览行] 边框 输入 …
        d.render((a, buf) -> paintLive(buf, tail, m), h, 4 + input.codePointCount(0, input.length()), inputRow);
        inputVersionSeen = inputVersion;
    }

    static volatile Mode currentMode = Mode.PRINTLN;

    /**
     * S2：整帧擦除重写式（Claude Code / Ink 的机制）：提交新行走底部滚屏，
     * 每次更新把整个 live 帧逐行 ESC[2K 擦写——<b>从不使用 ESC[1L 插行</b>。
     */
    static void runInk(int seconds) throws Exception {
        try (Backend b = BackendFactory.create()) {
            b.enableRawMode();
            startInputReader(b);
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            long n = 0;
            long lastFrame = 0;
            // 先把帧画到当前光标处
            writeInkFrame(b, "", 0);
            while (System.nanoTime() < end && !stop.get()) {
                boolean newLine = System.nanoTime() - lastFrame > 80_000_000L;
                if (newLine || inputVersion != inputVersionSeen) {
                    writeInkFrame(b, newLine ? "ink " + n++ + " Claude Code 式整帧擦写输出内容" : "", 0);
                    inputVersionSeen = inputVersion;
                    if (newLine) lastFrame = System.nanoTime();
                }
                Thread.sleep(10);
            }
        }
    }

    /** Ink 式整帧输出：定位到帧顶，逐行 ESC[2K + 内容 + CRLF，末行 CRLF 触发滚屏提交。 */
    static void writeInkFrame(Backend b, String committedLine, long frameTick) {
        StringBuilder sb = new StringBuilder();
        String[] rows = inkRows(committedLine);
        sb.append("\r\033[").append(rows.length).append('A');           // 回帧顶
        for (String row : rows) {
            sb.append("\033[2K").append(row).append("\r\n");            // 擦整行+写+换行
        }
        sb.append("\r\033[").append(rows.length - 2).append('A');     // 光标回输入行（第 3 行）
        try {
            b.writeRaw(sb.toString());
            b.flush();
        } catch (Exception ignored) { }
    }

    static String[] inkRows(String committed) {
        String inp = "│ > " + input + "▏";
        String pad = "────────────────────────────────────────────";
        return new String[] {
                committed,
                "╭" + pad + "╮",
                inp,
                "│                                            │",
                "╰" + pad + "╯",
                "status: ink 场景 · 已发送 " + sent.get() + " · 打字回显中 · Ctrl+C 退出",
        };
    }

    /** 画 code-tui 近似的 live 区：预览行(可选)+圆角输入框(含回显)+状态行；光标在输入行。 */
    static void paintLive(Buffer buf, String previewTail, Mode mode) {
        int w = buf.width();
        int row = 0;
        if (!previewTail.isEmpty()) setRow(buf, row++, "  " + previewTail);
        String pad = "─".repeat(Math.max(0, w - 2));
        setRow(buf, row++, "╭" + pad + "╮");
        setRow(buf, row++, "│ > " + input + "▏");
        setRow(buf, row++, "│" + " ".repeat(Math.max(0, w - 2)) + "│");
        setRow(buf, row++, "╰" + pad + "╯");
        setRow(buf, row, "status: " + mode + " · 已发送 " + sent.get() + " · 在框内打字(中文) · Ctrl+C 退出");
    }

    static void setRow(Buffer buf, int y, String s) {
        buf.setLine(0, y, Text.raw(s).lines().get(0));
    }

    private ScenarioStress() { }
}

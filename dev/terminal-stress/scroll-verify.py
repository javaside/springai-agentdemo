#!/usr/bin/env python3
"""SCROLL_APPEND 模式的无头屏幕验证（v3，重写规避嵌套转义）：
驱动程序先 println×26 <空白行> 预填滚到屏底（live 区贴底前提；用空白行是因为
上方内容行会被 ghost 断言判为残影），渲染 6 行帧，println 8 条消息；
pty 字节喂进极简 VT100 仿真器，断言：底 6 行==帧、上方 8 条消息、无残影、无 ESC[1L。
2026-10-19 起首帧不再钉底（ESC[999B 已移除，见 InlineDisplay.render 注释），
本脚本验证的「贴底滚动」前提必须由预填自己保证，不能再依赖首帧钉底。
"""
import fcntl, os, pty, re, select, signal, struct, subprocess, sys, termios, time

CP = open("/tmp/stress-cp.txt").read().strip()
# patch 模块自身 classes 必须前置在依赖 jar 之前——shadow 机制全靠 classpath 顺序。
# 否则 InlineDisplay 会解析到上游 tamboui-core-0.4.0.jar 里的旧实现（println 走
# ESC[1L 插行），「零插行」断言必挂，且挂因极其隐蔽（2026-10-19 实测踩中）。
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLASSES = os.path.join(REPO, "springai-tamboui-inline-patch", "target", "classes")
FULL_CP = CLASSES + os.pathsep + "/tmp" + os.pathsep + CP

DRIVER_LINES = [
    "import dev.tamboui.buffer.Buffer;",
    "import dev.tamboui.inline.InlineDisplay;",
    "import dev.tamboui.terminal.Backend;",
    "import dev.tamboui.terminal.BackendFactory;",
    "import dev.tamboui.text.Text;",
    "",
    "public class ScrollDriver {",
    "    public static void main(String[] a) throws Exception {",
    "        for (int i = 0; i < 26; i++) System.out.println();   // 预填空白滚到屏底（无钉底后 live 贴底的前提）",
    "        try (Backend b = BackendFactory.create()) {",
    "            b.enableRawMode();",
    "            InlineDisplay d = InlineDisplay.withBackend(6, b);",
    "            d.render((area, buf) -> paint(buf), 6, 4, 1);",
    "            b.flush();",
    "            for (int i = 1; i <= 8; i++) {",
    "                Thread.sleep(60);",
    '                d.println("MSG-" + i);',
    "                b.flush();",
    "            }",
    "            Thread.sleep(200);",
    "        }",
    '        System.out.println("BYEBYE");',
    "    }",
    "    static void paint(Buffer buf) {",
    '        String pad = "-".repeat(Math.max(0, buf.width() - 2));',
    '        row(buf, 0, "+" + pad + "+");',
    '        row(buf, 1, "| > input |");',
    '        row(buf, 2, "|" + " ".repeat(Math.max(0, buf.width() - 2)) + "|");',
    '        row(buf, 3, "|" + " ".repeat(Math.max(0, buf.width() - 2)) + "|");',
    '        row(buf, 4, "+" + pad + "+");',
    '        row(buf, 5, "status-line");',
    "    }",
    "    static void row(Buffer buf, int y, String s) {",
    "        buf.setLine(0, y, Text.raw(s).lines().get(0));",
    "    }",
    "}",
]
open("/tmp/ScrollDriver.java", "w").write("\n".join(DRIVER_LINES))
subprocess.run(["javac", "-cp", FULL_CP, "-d", "/tmp", "/tmp/ScrollDriver.java"], check=True)

W, H = 100, 30
master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", H, W, 0, 0))
p = subprocess.Popen(["java", "-cp", FULL_CP, "ScrollDriver"],
                     stdin=slave, stdout=slave, stderr=slave,
                     env=dict(os.environ))
os.close(slave)
out = b""
t0 = time.time()
while time.time() - t0 < 15:
    r, _, _ = select.select([master], [], [], 0.5)
    if r:
        try:
            d = os.read(master, 65536)
            if not d:
                break
            out += d
        except OSError:
            break
    if b"BYEBYE" in out:
        break
try:
    os.kill(p.pid, signal.SIGKILL)
except Exception:
    pass

text = out.decode("utf-8", "replace")
assert "\x1b[1L" not in text, "ESC[1L must be absent in scroll-append mode"

grid = [[" "] * W for _ in range(H)]
cy = cx = 0
i, n = 0, len(text)
while i < n:
    c = text[i]
    if c == "\x1b":
        m = re.match(r"\x1b\[([0-9;?<>=]*)([\x20-\x2f]*)([@-~])", text[i:])
        if m:
            params, cmd = m.group(1), m.group(3)
            num = int(params) if params.isdigit() else 1
            if cmd == "A":
                cy = max(0, cy - num)
            elif cmd == "B":
                cy = min(H - 1, cy + num)
            elif cmd == "C":
                cx = min(W - 1, cx + num)
            elif cmd == "D":
                cx = max(0, cx - num)
            elif cmd == "K":
                k = int(params) if params.isdigit() else 0
                if k == 0:
                    grid[cy] = grid[cy][:cx] + [" "] * (W - cx)
                elif k == 2:
                    grid[cy] = [" "] * W
            i += m.end()
            continue
        i += 1
        continue
    if c == "\r":
        cx = 0
    elif c == "\n":
        if cy == H - 1:
            grid.pop(0)
            grid.append([" "] * W)
        else:
            cy += 1
    elif c >= " ":
        if cx < W:
            grid[cy][cx] = c
        cx += 1
    i += 1

rows = ["".join(r).rstrip() for r in grid]
print("== screen bottom 12 rows ==")
for r in rows[-12:]:
    print(repr(r[:60]))

msgs = [f"MSG-{k}" for k in range(1, 9)]
frame_rows = rows[H - 6:]
ok_frame = (frame_rows[0].startswith("+") and frame_rows[1].startswith("|")
            and frame_rows[4].startswith("+") and frame_rows[5].startswith("status"))
above = rows[:H - 7]
ok_msgs = [r for r in above if r.startswith("MSG-")]
ghosts = [r for r in above if not r.startswith("MSG-") and r.strip() and not r.startswith("BYEBYE")]
print("frame ok:", ok_frame)
print("msg rows on screen:", len(ok_msgs), "(expect 7~8; MSG-1 可被滚出 30 行视口进 scrollback)")
print("row just above frame:", repr(rows[H - 7]))
print("ghost rows above frame:", ghosts if ghosts else "none")
sys.exit(0 if (ok_frame and len(ok_msgs) >= 7
               and rows[H - 7].startswith("MSG-8") and not ghosts) else 1)

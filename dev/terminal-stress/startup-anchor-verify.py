#!/usr/bin/env python3
"""启动锚定契约的无头屏幕验证（2026-10-19 随「首帧不钉底」一起引入）：

空的高窗口（100x45）里直接创建 InlineDisplay（等价 code-tui 启动首帧），
渲染 4 行 live 区，println 12 条横幅行；pty 字节喂进极简 VT100 仿真器，断言：

- BANNER-1 落在第 0 行（锚定在启动光标处，而非 ESC[999B 钉底后从屏幕底部往上长）；
- 横幅行连续（0..11），live 区紧随其后（12..15），空隙在内容<b>下方</b>——
  与 resize 重放（ScreenCleaner + ESC[H 顶对齐）观感一致，修「启动上半屏空白」；
- 字节流无 ESC[999B（钉底已移除）且无 ESC[1L（纯滚动模式零插行）；
- 横幅上方无残影。

前置：/tmp/stress-cp.txt（patch 模块依赖 classpath，
`mvn -pl springai-tamboui-inline-patch dependency:build-classpath -Dmdep.outputFile=/tmp/stress-cp.txt`）；
patch 模块自身 classes 由脚本相对路径定位并<b>前置</b>在依赖 jar 之前——shadow 机制
全靠 classpath 顺序，漏前置会静默跑到 tamboui-tui 上游 jar 的旧实现
（指纹：字节流里出现 ESC[1L）。
"""
import fcntl, os, pty, re, select, signal, struct, subprocess, sys, termios, time

CP = open("/tmp/stress-cp.txt").read().strip()
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLASSES = os.path.join(REPO, "springai-tamboui-inline-patch", "target", "classes")

DRIVER_LINES = [
    "import dev.tamboui.buffer.Buffer;",
    "import dev.tamboui.inline.InlineDisplay;",
    "import dev.tamboui.terminal.Backend;",
    "import dev.tamboui.terminal.BackendFactory;",
    "import dev.tamboui.text.Text;",
    "",
    "public class StartupDriver {",
    "    public static void main(String[] a) throws Exception {",
    "        try (Backend b = BackendFactory.create()) {",
    "            b.enableRawMode();",
    "            InlineDisplay d = InlineDisplay.withBackend(4, b);",
    "            d.render((area, buf) -> paint(buf), 4, 4, 1);",
    "            b.flush();",
    "            for (int i = 1; i <= 12; i++) {",
    "                Thread.sleep(40);",
    '                d.println("BANNER-" + i);',
    "                b.flush();",
    "            }",
    "            Thread.sleep(200);",
    "        }",
    '        System.out.println("BYEBYE");',
    "    }",
    "    static void paint(Buffer buf) {",
    '        String pad = " ".repeat(Math.max(0, buf.width() - 2));',
    '        row(buf, 0, "+" + "-".repeat(Math.max(0, buf.width() - 2)) + "+");',
    '        row(buf, 1, "| >" + pad + "|");',
    '        row(buf, 2, "+" + "-".repeat(Math.max(0, buf.width() - 2)) + "+");',
    '        row(buf, 3, "status");',
    "    }",
    "    static void row(Buffer buf, int y, String s) {",
    "        buf.setLine(0, y, Text.raw(s).lines().get(0));",
    "    }",
    "}",
]
open("/tmp/StartupDriver.java", "w").write("\n".join(DRIVER_LINES))
subprocess.run(["javac", "-cp", CLASSES + os.pathsep + CP, "-d", "/tmp",
                "/tmp/StartupDriver.java"], check=True)

W, H = 100, 45
master, slave = pty.openpty()
fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", H, W, 0, 0))
p = subprocess.Popen(["java", "-cp", CLASSES + os.pathsep + "/tmp" + os.pathsep + CP,
                      "StartupDriver"],
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
print(f"== full screen ({H} rows, 0-indexed) ==")
for idx, r in enumerate(rows):
    print(f"{idx:2d} |{r[:70]}")

banner_rows = [i for i, r in enumerate(rows) if r.startswith("BANNER-")]
frame_top = next((i for i, r in enumerate(rows) if r.startswith("+---")), -1)
ghosts = [r for r in rows if r.strip() and not r.startswith("BANNER-")
          and not r.startswith(("+", "| >", "status", "BYEBYE"))]
print()
print("banner rows:", (banner_rows[0], banner_rows[-1]) if banner_rows else "none")
print("live frame top row:", frame_top)
print("ghost rows:", ghosts if ghosts else "none")
print("ESC[999B present:", "\x1b[999B" in text)
print("ESC[1L present:", "\x1b[1L" in text)

ok = (len(banner_rows) == 12
      and banner_rows[0] == 0                       # 锚定启动光标处（第 0 行）
      and banner_rows == list(range(12))            # 连续无跳行
      and frame_top == 12                           # live 区紧随内容，空隙在下方
      and "\x1b[999B" not in text                   # 钉底已移除
      and "\x1b[1L" not in text                     # 纯滚动零插行
      and not ghosts)
sys.exit(0 if ok else 1)

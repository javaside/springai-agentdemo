#!/usr/bin/env python3
"""PTY smoke test for 用户贴图的附件行 + Ctrl+G 取消。

<b>这个脚本存在的唯一理由</b>：证明 `Ctrl+G` 这个按键<b>真的能到达应用</b>。

单测原理上证明不了这件事。本项目的单测入口 `feedKeyForTest` 直接调
`InputBox.handleKeyEvent`，<b>绕过了 TamboUI 的按键路由器</b>；而按键要先过路由器
（Bindings → Actions），被默认绑定吃掉的键根本走不到 `onInputKey`。前科在此：
`Tab` / `Shift+Tab` 曾被默认的 `FOCUS_NEXT` / `FOCUS_PREVIOUS` 先行消费，于是
斜杠菜单的 Tab 补全、`/mcp` 面板的 Tab 展开<b>长期是死代码而无人发现</b>——因为
单测走的正是绕过路由器的那个入口。`Ctrl+G`（BEL，0x07）面临完全相同的风险，
只有真伪终端能给出答案。

覆盖五条（编号与断言输出一一对应）：
  1. 输入一个真实图片的相对路径 → 附件行出现，含「已附带 1 张图片」「Ctrl+G 取消」；
  2. 按 Ctrl+G（发 0x07）→ 附件行变成「已取消附件」；★ 本任务的意义所在
  3. 取消后继续打字（路径仍在文本里）→ <b>不该</b>退回「已附带」（取消态在本次输入内保持）；
  4. 清空输入、重新输入同一路径 → 附件行<b>重新出现</b>（取消态已复位，不是永久失效）；
  5. 输入一个非图片路径（pom.xml）→ <b>不出现</b>附件行。

不需要模型、不需要网络：全程只在输入框里打字，一次都不按 Enter。

Usage:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
    python3 src/test/resources/scripts/attachment_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>"
otherwise.  失败时会把最后一屏打出来给人肉眼看。
"""
import os
import struct
import sys
import tempfile
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
)

# Ctrl+G = BEL = 0x07。EventParser 把 1..26 的控制字符还原成 Ctrl+字母
# （'a' + c - 1），7 → 'g'，所以应用侧的判据是 k.hasCtrl() && k.isChar('g')。
# <b>这一步只是解码；键能不能穿过路由器是另一回事，正是本脚本要测的。</b>
CTRL_G = b"\x07"
CTRL_U = b"\x15"                 # 删到行首：用来清空输入框（Ctrl+A/E/U 是本项目的 readline 键位）

# 输入框用 BorderType.ROUNDED，左下角是 ╰。附件行画在<b>盒子底边的下一行</b>
# （见 CodeTuiView.InputBox#render：boxRect 高度减 1，腾出的最后一行给附件行）。
BOX_BOTTOM_LEFT = "╰"

ATTACHED = "已附带"
CANCEL_HINT = "Ctrl+G 取消"
CANCELLED = "已取消附件"
ATTACH_MARK = "⏎"               # 附件行（两种形态）共有的行首标记，用于「这一行压根不是附件行」的否定断言

IMG_NAME = "shot.png"
TXT_NAME = "pom.xml"


# ── 造一张真图 ────────────────────────────────────────────────────────────
def make_png(path, width=4, height=3):
    """现造一张最小但<b>完全合法</b>的 PNG（签名 + IHDR + IDAT + IEND）。

    <b>为什么不往仓库塞二进制夹具</b>：夹具会被各种「清理二进制」的钩子和 review
    盯上，而这里需要的只是几十字节。<b>为什么必须是真 PNG 而不是改后缀的文本</b>：
    识别器走 Apache Tika 的<b>魔数</b>嗅探（MagicSniffer → kind == IMAGE），
    根本不看扩展名——一个叫 a.png 的文本文件不会被附上。
    """
    raw = b"".join(b"\x00" + b"\xff\x00\x00" * width for _ in range(height))  # 每行前缀 filter=0

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw))
           + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    return png


def self_test_png(png):
    """先证明造出来的确实是 PNG，再拿去喂应用。

    否则断言 1 失败时会有两种解释（键盘链路坏了 / 图造废了），而失败信息分辨不出来。
    这里只能验签名 + 结构（Tika 认 PNG 靠的就是头 8 字节签名）；<b>Tika 真的收不收，
    最终由断言 1 本身作答</b>——附件行只在 kind == IMAGE 时才会画出来。
    """
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        die("self-test: 造出来的文件不是 PNG（签名不对）")
    if b"IHDR" not in png[:20] or not png.endswith(b"IEND\xae\x42\x60\x82"):
        die("self-test: PNG 结构不完整（缺 IHDR 或 IEND）")


# ── 屏幕助手 ──────────────────────────────────────────────────────────────
def find_row(session, needle):
    """含 `needle` 的<b>最后</b>一个屏幕行号，找不到返回 -1。

    取最后一个而非第一个：本脚本用它定位<b>活的</b>输入框，而输入框永远钉在屏幕底部、
    scrollback 在它上面。取首个匹配会拿到历史里的旧盒子。同 permission_smoke.py。
    """
    hit = -1
    for i, line in enumerate(session.screen.display):
        if needle in line:
            hit = i
    return hit


def input_box_bottom(session):
    """活输入框底边所在的行号——本脚本所有断言的锚点。"""
    y = find_row(session, BOX_BOTTOM_LEFT)
    if y < 0:
        die("屏幕上找不到输入框底边（%r）——界面没起来？" % BOX_BOTTOM_LEFT,
            session.screen.display)
    return y


def assert_rows_below(session, anchor, needles, what):
    """从 `anchor` 行的下一行起，逐行核对 `needles`——顺序与「各占一物理行」一起钉住。

    <b>为什么不用全屏子串</b>：`screen_text()` / `wait_for` 都看得见 scrollback，
    「已附带」在断言 1 就出现过一次，往后任何一条基于全屏子串的断言都会被那条历史
    喂成假绿。锚定到<b>当前</b>输入框底边再往下数一行，历史一概进不来。
    照抄 permission_smoke.py 的同名助手。
    """
    for offset, needle in enumerate(needles, start=1):
        row = anchor + offset
        if row >= len(session.screen.display) or needle not in session.screen.display[row]:
            actual = session.screen.display[row] if row < len(session.screen.display) else "<越界>"
            die("%s：第 %d 行应含 %r，实际 %r" % (what, row, needle, actual),
                session.screen.display)


def assert_no_rows_below(session, anchor, needles, what):
    """`assert_rows_below` 的否定版：锚点下一行<b>不得</b>含这些串。

    否定断言比肯定断言更需要锚定：「屏幕上没有 X」在有 scrollback 的终端里几乎永远
    为假（X 早出现过），只有「<b>当前这一行</b>没有 X」才是可判定的命题。
    """
    row = anchor + 1
    line = session.screen.display[row] if row < len(session.screen.display) else ""
    for needle in needles:
        if needle in line:
            die("%s：第 %d 行不该含 %r，实际 %r" % (what, row, needle, line.rstrip()),
                session.screen.display)


def type_text(session, text, settle=1.0):
    """打字并等渲染落定。识别跑在每次击键上（读盘 + 魔数嗅探），要给它时间。"""
    session.write(text.encode("utf-8"))
    session.pump(settle)


def wait_row_below(session, needle, timeout, what):
    """等到<b>活输入框正下方那一行</b>出现 `needle`。

    不是 `wait_for(needle)`：那个查全屏，会被 scrollback 里的旧附件行立刻喂饱而
    直接返回——断言 4「重新出现」就会在什么都没发生的情况下变绿。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        session.pump(0.2)
        y = find_row(session, BOX_BOTTOM_LEFT)
        if y >= 0 and y + 1 < len(session.screen.display) \
                and needle in session.screen.display[y + 1]:
            return
        if session.proc.poll() is not None:
            die("进程提前退出（code=%s），当时在等 %s"
                % (session.proc.returncode, what), session.screen.display)
    die("超时未等到 %s（期望输入框下一行含 %r）" % (what, needle), session.screen.display)


def clear_input(session):
    """清空输入框：光标在行尾，Ctrl+U 删到行首即可。

    顺带复位取消态——`attachmentLineText()` 在识别不到图时会把 attachmentsCancelled
    置回 false（用户删掉路径再重写一条，理应重新附上）。断言 4 依赖的正是这条。
    """
    session.write(CTRL_U)
    session.pump(0.6)


# ── 场景 ─────────────────────────────────────────────────────────────────
def check_attachment_appears(session):
    """断言 1：输入真实图片的相对路径 → 附件行出现。

    顺带把文件名一起断言（「（shot.png）」）：只查「已附带」的话，任何别的东西
    恰好含这三个字都能让它变绿；把名字钉上，这一行就只可能是我们这张图产生的。
    """
    type_text(session, IMG_NAME)
    wait_row_below(session, ATTACHED, 15, "附件行出现")
    anchor = input_box_bottom(session)
    assert_rows_below(session, anchor,
                      ["已附带 1 张图片（%s）" % IMG_NAME], "断言 1：附件行内容")
    if CANCEL_HINT not in session.screen.display[anchor + 1]:
        die("断言 1：附件行里没有 %r —— 用户看不到撤销入口" % CANCEL_HINT,
            session.screen.display)
    print("断言 1 OK：附件行出现，含「已附带 1 张图片（%s）」与「%s」." % (IMG_NAME, CANCEL_HINT))


def check_ctrl_g_cancels(session):
    """断言 2：按 Ctrl+G → 附件行变成「已取消附件」。★ 本脚本的意义所在

    <b>这一条失败不等于脚本写错了</b>：它恰恰是这个任务要找的东西——键被路由器
    吃掉了。真出现的话别改脚本，去看 configure() 里要不要 unbind 抢键的默认绑定
    （Tab/Shift+Tab 当年就是这么修的）。
    """
    session.write(CTRL_G)
    wait_row_below(session, CANCELLED, 8,
                   "Ctrl+G 后附件行变成「已取消附件」"
                   "（若超时：键很可能被 TamboUI 路由器吃掉了，别急着改脚本）")
    anchor = input_box_bottom(session)
    assert_rows_below(session, anchor, [CANCELLED], "断言 2：取消后的附件行")
    # 取消后刻意不再提示 Ctrl+G（已经取消了，再提示是噪音）——顺手钉住，
    # 这样「附件行根本没变、只是碰巧含别的字」这种解释也被排除。
    assert_no_rows_below(session, anchor, [ATTACHED, CANCEL_HINT],
                         "断言 2：取消后不该再显示已附带/撤销提示")
    print("断言 2 OK：Ctrl+G 到达了应用，附件行变成「%s」." % CANCELLED)


def check_cancel_sticks_while_typing(session):
    """断言 3：取消后继续打字 → 不该退回「已附带」。

    <b>补的字必须让路径依然成立</b>（这里补一段中文说明，`shot.png` 仍是独立词元）。
    若随手补个字母把路径打坏成 `shot.pngx`，识别结果为空、附件行整行消失，
    「没有『已附带』」就会在功能全坏的情况下也成立——那是典型的因错误理由通过。
    """
    type_text(session, " 请看这张图")
    anchor = input_box_bottom(session)
    # 先正面确认附件行<b>还在</b>（= 路径仍被识别），否定断言才有意义。
    assert_rows_below(session, anchor, [CANCELLED], "断言 3：继续打字后仍是取消态")
    assert_no_rows_below(session, anchor, [ATTACHED],
                         "断言 3：取消态不该被后续击键冲掉")
    print("断言 3 OK：取消态在本次输入内保持（路径仍在文本里，行仍是「%s」）." % CANCELLED)


def check_cancel_resets_after_clear(session):
    """断言 4：清空输入、重新输入同一路径 → 附件行重新出现（取消不是永久失效）。"""
    clear_input(session)
    anchor = input_box_bottom(session)
    assert_no_rows_below(session, anchor, [ATTACHED, CANCELLED],
                         "断言 4：清空后附件行应当消失")
    type_text(session, IMG_NAME)
    wait_row_below(session, ATTACHED, 10, "重新输入后附件行重新出现")
    anchor = input_box_bottom(session)
    assert_rows_below(session, anchor,
                      ["已附带 1 张图片（%s）" % IMG_NAME], "断言 4：重新出现的附件行")
    print("断言 4 OK：清空后取消态复位，同一路径重新附上.")


def check_non_image_ignored(session):
    """断言 5：非图片路径不出现附件行。

    这一条单看可能因错误理由通过（比如整条链路都坏了，什么都不显示）。它的把关
    靠的是<b>同一次会话里断言 1/4 刚证明过附件行会出现</b>——机制活着，这里仍不出现，
    才说明是魔数判据把 pom.xml 挡住了，而不是功能死了。
    """
    clear_input(session)
    type_text(session, TXT_NAME, settle=1.5)
    anchor = input_box_bottom(session)
    assert_no_rows_below(session, anchor, [ATTACH_MARK, ATTACHED, CANCELLED],
                         "断言 5：非图片路径不该产生附件行")
    print("断言 5 OK：%r 不被识别为图片，无附件行." % TXT_NAME)


def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-attach-smoke-")
    # 隔离用户层：真实的 ~/.codetui（AGENTS.md、会话、权限规则）会改变启动后的屏幕内容。
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    png = make_png(os.path.join(tmpdir, IMG_NAME))
    self_test_png(png)
    # 非图片对照物：真的写成 XML 文本。带魔数的文本（pom.xml 正是 Tika 认得的一种）
    # 更能考验判据——它不是「什么都识别不出」的空文件。
    with open(os.path.join(tmpdir, TXT_NAME), "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n<project><name>smoke</name></project>\n')
    print("图片 %s (%d bytes)，对照文件 %s" % (IMG_NAME, len(png), TXT_NAME))

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"          # pty.fork 默认窗口 0×0 + 无 TERM = 渲染全空白
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    # 全程不按 Enter，模型不会被调用；这里只是别让开发机上的其它 key 改变启动路径。
    for k in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY"):
        env.pop(k, None)

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=30)
        session.pump(1.0)
        print("Startup OK.")

        check_attachment_appears(session)
        check_ctrl_g_cancels(session)
        check_cancel_sticks_while_typing(session)
        check_cancel_resets_after_clear(session)
        check_non_image_ignored(session)

        print_screen("FINAL SCREEN", session.screen.display)

        clear_input(session)
        session.write(b"/exit\r")
        deadline = time.time() + 10
        while time.time() < deadline and session.proc.poll() is None:
            session.pump(0.2)

        print("SMOKE PASS (5 断言)")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

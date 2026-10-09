#!/usr/bin/env python3
"""keycap / VS16 组合序列的 PTY 实机冒烟：<b>粘贴事故文案后输入框不错位、打字不崩</b>。

用户实报（2026-10-09）：在 xibaojun 项目粘贴 `🍺9月7日周一赛事🍺⚽足球赛事9️⃣场⚽…8️⃣场…`
到输入框后「输入框就乱了，再打字终端就崩溃」，宿主 Terminal.app 主线程 SIGBUS
（Terminal-2026-10-09-085041.ips）。根因：keycap（U+0039+FE0F+20E3）被逐码点口径
算 1 列、终端画 2 列，输入框从序列处开始逐列错位。

修复（本分支）：TerminalWidth 序列口径 + shadow Buffer 2 列布格 + code-tui 全部
排版消费方换口径。本脚本验证<b>实机形态</b>——单测证明不了的三件事：

  1. <b>粘贴的原文完整进框</b>（粘贴→TextAreaState→渲染整链路没丢字符、没切半序列：
     屏上能找回完整的 9️⃣/8️⃣/⚠️ 字节序列）。
  2. <b>打字不崩</b>（事故的直接症状：粘贴后再敲键，进程必须活着、屏幕结构稳定）。
  3. <b>输入框边框闭合</b>（错位的外显形态之一是行超宽/撕裂；边框四角完整是
     最朴素的完整性探针）。

  ⚠ 不判红项：pyte 自带一套 wcwidth，与 TerminalWidth / Terminal.app 的口径
  各自独立。屏上「视觉列位置」的 oracle 偏差只打印观测、不当断言（经验来自
  table_render_smoke 第 3 条教训：别拿 pyte 当真相）。

不需要真实 key、不需要网络（不提交回合，无模型调用）。

运行前<b>必须重新 package</b>（跑的是 target/classes，patch 模块还需 install），否则
跑的是旧字节码——本脚本恰恰验的是「新布格」，旧字节码上跑会看到事故复现：

    mvn -q -pl springai-tamboui-inline-patch install -DskipTests
    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/keycap_smoke.py
"""
import importlib.util
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

ROWS, COLS = 30, 80

# 2026-10-09 事故原文案（用户粘贴内容，一字未改）
INCIDENT = ("🍺9月7日周一赛事🍺⚽足球赛事9️⃣场⚽🏀篮球赛事8️⃣场🏀"
            "⏰晚上 22:00点⏰ ")
# 事故真实形态：微信 macOS 复制的多行文案行尾是<b>纯 CR</b>（会话 JSON 逐字节实证
# 🍺…\r⚽…），粘贴普通路径曾把 \r 原样落进输入框（2026-10-09 二次事故）。此样本
# 钉住「CR 归一成换行」的完整链路：输入框按 \n 分行、打印流不含裸 CR。
INCIDENT_RAW_CR = ("🍺9月7日周一赛事🍺\r⚽足球赛事9️⃣场⚽\r🏀篮球赛事8️⃣场🏀\r"
                   "⏰晚上 22:00点⏰ 都觉得大姐夫大姐夫的到付件大大李逵负荆")
# VS16 组合样本（⚠+FE0F）：Terminal.app DSR 实测 1 列（与 wcwidth 分歧，按实机建模）。
# 完整字节断言仍然有效——cell symbol 须含完整组合，宽度口径不在本脚本判红范围。
VS16_SAMPLE = "警告⚠\ufe0f注意"

PASTE_START = b"\x1b[200~"
PASTE_END = b"\x1b[201~"


def screen_text(session):
    """全屏拼成一个字符串（找子串用；忽略样式）。"""
    lines = [row.rstrip() for row in session.screen.display]
    return "\n".join(lines)


def assert_box_intact(session, label):
    """输入框圆角边框四角完整（错位/撕裂的最朴素完整性探针）。"""
    lines = [row.rstrip() for row in session.screen.display]
    tops = [i for i, ln in enumerate(lines) if "╭" in ln and "╮" in ln]
    bots = [i for i, ln in enumerate(lines) if "╰" in ln and "╯" in ln]
    if not tops or not bots:
        rs.die("[%s] 输入框边框不完整：top=%s bottom=%s（行超宽被折/撕裂的形态）"
               % (label, tops, bots), lines)
    return tops[0], bots[0]


def main():
    classpath = rs.build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-keycap-smoke-")
    home = os.path.join(tmpdir, "home")
    os.makedirs(home, exist_ok=True)

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"   # 只过启动检查；不提交回合不调用
    env["DEEPSEEK_MODELS"] = "deepseek-chat"
    for key in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY",
                "OPENAI_API_KEY", "OPENCODE_GO_API_KEY"):
        env.pop(key, None)

    cmd = ["java", "-Duser.home=" + home, "-Dcodetui.hardwareCursor=always",
           "-cp", classpath, rs.MAIN_CLASS]
    print("Launching (%dx%d)" % (ROWS, COLS))
    session = rs.PtySession(cmd, tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(rs.WELCOME, timeout=40)
        session.wait_stable(quiet=0.8)

        # ── 1. 粘贴事故文案（bracketed paste，同终端 Cmd+V 的真实路径） ──
        payload = INCIDENT.encode()
        session.write(PASTE_START + payload + PASTE_END)
        session.wait_stable(quiet=1.0, timeout=8)
        # 完整性判据用 raw 字节流（app 写往终端的全部字节）：pyte 的 display 是
        # 「每 cell 一字符」模型，组合码点（FE0F/20E3）被并入 cell 后不再出现在
        # display 字符串里——display 找子串会漏报，raw 才是不掺假的真相。
        for seq, name in [("9\ufe0f\u20e3".encode(), "9️⃣ keycap"),
                          ("8\ufe0f\u20e3".encode(), "8️⃣ keycap"),
                          ("🍺".encode(), "🍺 emoji"), ("⏰".encode(), "⏰ emoji")]:
            if seq not in session.raw:
                rs.die("app 输出流里没有完整的 %s 字节——cell symbol 被切半或丢失" % name,
                       screen_text(session).splitlines())
        print("粘贴完整 OK: 9️⃣/8️⃣ keycap 与全部 emoji 的完整字节都在输出流里")

        # keycap 后不得有多余列：差分渲染游标若按 CharWidth（=1）而非 TerminalWidth（=2）
        # 记 keycap，会补发一个 ESC[1C，屏上表现为「9️⃣ 场」中间多一个空格、每行溢 1 列/keycap
        # ——Terminal.app 缓冲越界卡死整机的事故形态（2026-10-09）。故这里判「9️⃣ 与场紧邻」。
        raw_text = session.raw.decode("utf-8", "replace")
        for seq, name in [("9️⃣场", "9️⃣ 与场"), ("8️⃣场", "8️⃣ 与场")]:
            if seq not in raw_text.replace("\u001b[0;7m", ""):
                # 光标带修复会整行重写，可能夹一个反显空格；再放宽查「不含 ESC[1C 夹在中间」
                bad = seq[0] + "\u001b[1C"
                if bad in raw_text:
                    rs.die("%s 之间出现 ESC[1C——差分游标把 keycap 记成 1 列，"
                           "每行多溢 1 列（Terminal.app 会缓冲越界卡死）" % name,
                           screen_text(session).splitlines())
                print("宽度旁证（仅观测）: %s 未紧邻，但无 ESC[1C 补发（可能被光标带重画拆分）" % name)
            else:
                print("keycap 列宽 OK: %s 紧邻，无多余光标前进（差分游标与终端同口径 2 列）" % name)
        assert_box_intact(session, "粘贴后")

        # ── 2. 打字（事故触发点：粘贴后再击键） ──
        session.write("测试xyz".encode())
        session.wait_stable(quiet=1.0, timeout=8)
        if session.proc.poll() is not None:
            rs.die("打字后进程退出（事故复现：exit=%s）" % session.proc.poll(),
                   screen_text(session).splitlines())
        # ⚠ pyte 无法建模 keycap 的 2 列（它按逐码点算 1），<b>含 keycap 的行</b>在 pyte
        # 屏幕上必然左右错位——对这类行只能用 app 原始输出流断言。历史教训：本脚本早先的
        # 「屏上有『9 场』」旁证能过，恰恰是因为 bug 的 ESC[1C 让 pyte 的 1 列模型蒙对了，
        # 等于在给 bug 背书。屏幕级真相由 Terminal.app 实测（DSR 宽度探针）负责。
        if "测试xyz".encode() not in session.raw:
            rs.die("打字内容未出现在 app 输出流（进程可能卡死）", screen_text(session).splitlines())
        if "场".encode() not in session.raw or "9️⃣".encode() not in session.raw:
            rs.die("打字后输入框文本丢失（raw 里找不到 keycap/场）", screen_text(session).splitlines())
        assert_box_intact(session, "打字后")
        print("打字不崩 OK: 进程存活、输入框边框完整、打字字节与粘贴文本同在输出流")

        # ── 2b. 事故真实形态：纯 CR 行尾的原文（微信 macOS 复制） ──
        # 粘贴后 CR 必须被归一成换行：输入框按 \n 分多行；随后 Enter 提交，
        # 打印流（app 写往终端的 raw 字节）里不得出现裸 CR（\r 后跟非 \n）。
        session.write(PASTE_START + INCIDENT_RAW_CR.encode() + PASTE_END)
        session.wait_stable(quiet=1.0, timeout=8)
        if session.proc.poll() is not None:
            rs.die("CR 原文粘贴后进程退出（exit=%s）" % session.proc.poll(),
                   screen_text(session).splitlines())
        text = screen_text(session)
        for piece in ("都觉得大姐夫", "⏰晚上"):
            if piece not in text.replace(" ", ""):
                rs.die("CR 原文内容「%s」不在屏上——归一失败或文本丢失" % piece,
                       text.splitlines())
        assert_box_intact(session, "CR 原文粘贴后")
        mark = len(session.raw)
        session.write(b"\r")     # 提交：dummy key，会打出 401 错误行——打印流在此期间生成
        session.wait_stable(quiet=1.5, timeout=15)
        # 行为级判据而非字节扫描：差分引擎自己也合法用 \r 回列 0 重画（\r+边框/空格），
        # 字节层区分不了「定位 CR」与「正文 CR」。而 pyte 是真终端模拟器——正文若带
        # CR 到达终端，CR 会执行、后续行从行首覆盖，消息内容必然残缺。故断言提交后
        # 屏上：归一后的每个段落都在（\n 分行的消息完整落进 scrollback，无覆盖丢失）。
        text = screen_text(session).replace(" ", "")
        for piece in ("都觉得大姐夫", "⏰晚上", "22:00"):
            if piece not in text:
                rs.die("提交后消息段「%s」不在屏上——正文 CR 覆盖 scrollback 或消息丢失" % piece,
                       screen_text(session).splitlines())
        assert_box_intact(session, "CR 原文提交后")
        print("CR 原文 OK: 粘贴归一成换行、提交后消息段落完整落屏")

        # ── 3. VS16 组合（⚠️）同样走一遍 ──
        session.write(PASTE_START + VS16_SAMPLE.encode() + PASTE_END)
        session.wait_stable(quiet=1.0, timeout=8)
        if session.proc.poll() is not None:
            rs.die("VS16 粘贴后进程退出（exit=%s）" % session.proc.poll(),
                   screen_text(session).splitlines())
        if "⚠\ufe0f".encode() not in session.raw:
            rs.die("⚠️ 组合字节不完整（被切半只剩 ⚠ 或只剩 FE0F）", screen_text(session).splitlines())
        assert_box_intact(session, "VS16 后")
        print("VS16 组合 OK: ⚠️ 完整字节在输出流、边框完整")

        print("\nALL OK: keycap/VS16 粘贴、打字、边框完整性全部通过")
    finally:
        try:
            session.proc.terminate()
        except OSError:
            pass


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""PTY smoke test：/model 选中的模型，重启后还在。

这条链只有真起两个进程才验得了：

  进程 A  启动 → 状态栏是默认模型 deepseek-v4-pro
          → /model → 数字快选第 2 项 → Enter
          → scrollback 出现「⚙ 已切换模型 · deepseek-v4-flash」
          → <root>/.codetui/model.json 落盘
          → /exit
  进程 B  同一个 cwd、**不带 -c** 启动
          → 状态栏一上来就是 deepseek-v4-flash
          → 且没有「现在不可用」的回退提示

为什么单测替代不了：restoreLastModel 可以写得完全正确，却因为在 main 里
插错了位置（例如插在 ProviderRegistry 构造之前、或 ConversationState
之前）而彻底不生效——那种错单测一条都不会红。

不需要真实 key、不需要网络：全程不发消息。

Usage:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/model_memory_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>".
"""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
)

DEFAULT_MODEL = "deepseek-v4-pro"      # DeepSeekProvider.models() 的第 1 项
PICKED_MODEL = "deepseek-v4-flash"     # 第 2 项——数字快选按 '2'
SWITCH_LINE = "⚙ 已切换模型"
FALLBACK_MARK = "现在不可用"           # restoreLastModel 的回退提示


def clean_env():
    """洗掉会改变模型清单的一切环境变量。

    多一家 provider 可用，/model 清单就多几行，数字快选的 '2' 就选到别的模型上
    去了——而脚本会静默通过，因为它只断言「第 2 项被选中」这件事的结果。
    """
    env = dict(os.environ)
    for key in list(env):
        if key.endswith("_API_KEY") or key.endswith("_BASE_URL") or key.endswith("_MODELS"):
            env.pop(key)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    return env


def launch(cmd, cwd, env, label):
    session = PtySession(cmd, cwd, env)
    session.wait_for(WELCOME_1, timeout=20)
    session.pump(0.8)
    print("%s: started" % label)
    return session


def main():
    classpath = build_classpath()
    workdir = tempfile.mkdtemp(prefix="codetui-modelmem-")
    home = tempfile.mkdtemp(prefix="codetui-modelmem-home-")
    env = clean_env()
    # 用户层配置（~/.codetui/）隔离掉，免得开发机上的真实配置影响启动。
    cmd = ["java", "-Duser.home=" + home, "-cp", classpath, MAIN_CLASS]

    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % workdir)

    failures = []

    # ── 进程 A：选一个非默认模型 ──────────────────────────────
    a = launch(cmd, workdir, env, "process A")
    try:
        text = a.screen_text()
        if DEFAULT_MODEL not in text:
            die("A: 状态栏没有默认模型 %r（前提不成立）" % DEFAULT_MODEL, a.screen.display)
        print("A: 默认模型 %s OK" % DEFAULT_MODEL)

        a.write(b"/model\r")
        a.wait_for("选择模型", timeout=10)
        a.write(b"2")
        a.pump(0.3)
        a.write(b"\r")
        # 断言的是「切换确认行 + 新模型名」这个组合：只找 PICKED_MODEL 会命中
        # 选择器面板里那一行（它一直就在屏幕上），证明不了「已经切过去了」。
        a.wait_for(SWITCH_LINE, timeout=10)
        a.pump(0.5)

        after = a.screen_text()
        if PICKED_MODEL not in after:
            failures.append("A: 切换后屏幕上没有 %r" % PICKED_MODEL)
        print_screen("A AFTER /model", a.screen.display)

        pref = os.path.join(workdir, ".codetui", "model.json")
        if not os.path.isfile(pref):
            failures.append("A: %s 没有落盘" % pref)
        else:
            with open(pref) as f:
                data = json.load(f)
            if data.get("lastModel") != PICKED_MODEL:
                failures.append("A: model.json 里是 %r，期望 %r"
                                % (data.get("lastModel"), PICKED_MODEL))
            else:
                print("A: model.json OK -> %s" % PICKED_MODEL)

        a.write(b"/exit\r")
        a.pump(1.5)
    finally:
        a.close()

    if failures:
        die("; ".join(failures))

    # ── 进程 B：同一目录重启，不带 -c ─────────────────────────
    b = launch(cmd, workdir, env, "process B")
    try:
        text = b.screen_text()
        print_screen("B STARTUP", b.screen.display)
        if PICKED_MODEL not in text:
            failures.append("B: 重启后状态栏不是 %r——记忆没生效" % PICKED_MODEL)
        if FALLBACK_MARK in text:
            failures.append("B: 冒出了回退提示 %r，说明恢复失败了" % FALLBACK_MARK)
        b.write(b"/exit\r")
        b.pump(1.5)
    finally:
        b.close()

    if failures:
        die("; ".join(failures))

    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

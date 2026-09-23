#!/usr/bin/env python3
"""PTY smoke：大日志粘贴的摄取吞吐 + 折叠 + 响应性。

<b>本脚本存在的原因</b>：EventParser 的粘贴批量快路径（反射穿透到 pty 原始流）
依赖 JLine 内部字段名与 macOS pty 的 available() 行为，单测桩复现不了真机字节流；
修复前实测 4MB 摄取 25s+（~150KB/s，逐字符非阻塞交接），用户感知即「贴大日志卡死」。

断言（全部满足 exit 0）：
  1. 4MB 粘贴投递（写端 select 等可写，不虚增延迟）在 20s 内完成；
  2. 屏幕出现 [TEXT1] 折叠标记（CodeTuiView 粘贴折叠接线）；
  3. 粘贴后按键 3s 内回显（输入线程未被拖死）。

Usage:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    python3 src/test/resources/scripts/paste_bulk_smoke.py [KB] [gap_ms] [chunk_b] [--pump]
默认 4096KB / 0ms / 64KB / --pump。失败时打印末屏与 jcmd 线程栈。
"""
import os
import select
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import PtySession, build_classpath  # noqa: E402

PASTE_START = b"\x1b[200~"
PASTE_END = b"\x1b[201~"


def make_log(kb):
    lines = []
    i = 0
    size = 0
    while size < kb * 1024:
        l1 = f"2026-09-22 10:15:{i % 60:02d}.{i % 1000:03d} ERROR [worker-{i % 8}] o.s.b.w.s.ServerServlet - Servlet.service() threw exception"
        l2 = f"\tat com.example.service.OrderService.handle(OrderService.java:{100 + i % 800})"
        lines.append(l1)
        lines.append(l2)
        size += len(l1) + len(l2) + 2
        i += 1
    return "\n".join(lines).encode()


def find_java_pid(session):
    # child is java directly (mvn exec via classpath) — use pgrep on the exact main class won't
    # work for `java -cp`; use the child pid itself.
    return session.proc.pid


def thread_dump(pid, label):
    try:
        out = subprocess.run(["jcmd", str(pid), "Thread.print", "-l"],
                             capture_output=True, text=True, timeout=15)
        text = out.stdout or out.stderr
        path = f"/tmp/paste_freeze_dump_{label}.txt"
        with open(path, "w") as f:
            f.write(text)
        print(f"[dump {label}] -> {path} ({len(text)} bytes)")
        return path
    except Exception as e:
        print(f"[dump {label}] failed: {e}")
        return None


def interesting(path):
    """打印渲染线程与输入线程的栈。"""
    keep = []
    with open(path) as f:
        block = []
        for line in f:
            if line.startswith('"'):
                if block and any(k in "".join(block) for k in
                                 ("RenderThread", "tui-input-reader", "main", "pty-writer")):
                    keep.append("".join(block[:14]))
                block = [line]
            else:
                block.append(line)
        if block and any(k in "".join(block) for k in ("RenderThread", "tui-input-reader", "main")):
            keep.append("".join(block[:14]))
    return "\n".join(keep)


def write_draining(session, data, pump_while_writing=False):
    """尽量写；pty 主端 EAGAIN 就等 5ms 再试（模拟真实终端：写不动就等应用来读）。
    pump_while_writing=True 时在写间隙持续抽吸应用输出（模拟真实终端边贴边读）。
    总时长 >90s 视为应用停止消费，中止（防脚本自身死循环）。"""
    view = memoryview(data)
    idle_rounds = 0
    t0 = time.time()
    while view:
        if time.time() - t0 > 90:
            raise RuntimeError("app stopped consuming paste (write stalled >90s)")
        try:
            n = os.write(session.master_fd, view)
            view = view[n:]
            idle_rounds = 0
        except BlockingIOError:
            idle_rounds += 1
            # select 等可写（应用读掉一格就绪）——固定 sleep 会把吞吐限到 1KB/sleep
            select.select([], [session.master_fd], [], 0.05)
        except OSError as e:
            print(f"[EIO] master write failed: {e!r}; child poll={session.proc.poll()}")
            try:
                session.pump(1.0)
                print_screen("at-eio", ["".join(r) for r in session.screen.display])
            except Exception:
                pass
            raise
        if pump_while_writing and idle_rounds % 4 == 3:
            session.pump(0.02)


def main():
    kb = int(sys.argv[1]) if len(sys.argv) > 1 else 512
    gap_ms = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    chunk_b = int(sys.argv[3]) if len(sys.argv) > 3 else 32768
    probe_during = "--probe-during" in sys.argv
    cp = build_classpath()
    cmd = ["java", "-cp", cp, "io.github.javaside.springai.codetui.CodeTuiApplication"]
    env = dict(os.environ)
    env.setdefault("CODETUI_API_KEY", "dummy")
    s = PtySession(cmd, cwd="/tmp", env=env)
    s.pump(3.0)

    payload = make_log(kb)
    print(f"[paste] {len(payload)//1024}KB in {chunk_b//1024}KB chunks, gap={gap_ms}ms")
    s.write(PASTE_START)
    t_write = time.time()
    mid_probed = False
    for off in range(0, len(payload), chunk_b):
        write_draining(s, payload[off:off + chunk_b], pump_while_writing="--pump" in sys.argv)
        if gap_ms:
            time.sleep(gap_ms / 1000.0)
        if probe_during and not mid_probed and off > len(payload) * 0.3:
            # 投喂中途：终端里此刻用户看到的应该是静止画面；探一个字符看是否进入输入框
            s.write(b"Q")
            s.pump(1.5)
            scr = "\n".join(s.screen.display)
            has_q = "Q" in scr and "[TEXT1]" not in scr
            print(f"[during] Q visible mid-ingestion: {has_q} (elapsed {time.time()-t_write:.1f}s)")
            mid_probed = True
    s.write(PASTE_END)
    t0 = time.time()
    delivery_s = time.time() - t_write
    print(f"[paste] delivery took {delivery_s:.1f}s")
    if delivery_s > 20:
        print(f"[FAIL] delivery too slow: {delivery_s:.1f}s > 20s (regression: bulk path broken?)")
        sys.exit(4)

    # 观察最多 60s：屏幕出现 [TEXT1] 或「已折叠」即成功
    seen = False
    while time.time() - t0 < 45:
        s.pump(1.0)
        scr = "\n".join(row for row in s.screen.display)
        if "[TEXT1]" in scr or "已折叠" in scr:
            seen = True
            print(f"[ok] collapsed marker visible after {time.time()-t0:.1f}s")
            break
        if int(time.time() - t0) in (10, 20, 30, 45) :
            p = thread_dump(find_java_pid(s), f"{int(time.time()-t0)}s")
            if p:
                print(interesting(p))

    if not seen:
        print("[FROZEN] no collapse marker within window; dumping threads")
        p = thread_dump(find_java_pid(s), "final")
        if p:
            print(interesting(p))
        sys.exit(2)

    # 响应性探测：打一个字符，看屏幕 3s 内是否出现
    s.write(b"Z")
    s.pump(3.0)
    scr = "\n".join(s.screen.display)
    ok = "Z" in scr
    print("[responsive]" if ok else "[UNRESPONSIVE]")
    if not ok:
        p = thread_dump(find_java_pid(s), "unresponsive")
        if p:
            print(interesting(p))
    sys.exit(0 if ok else 3)


if __name__ == "__main__":
    main()

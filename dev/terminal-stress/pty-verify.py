#!/usr/bin/env python3
"""无头验证 ScenarioStress 的真 tty 行为：spawn pty → 打字 → 检查回显帧 → Ctrl+C → 检查退出。"""
import os, pty, select, subprocess, sys, time

ROOT = "/Users/zxh/IdeaProjects/springai-agentdemo"
CP = open("/tmp/stress-cp.txt").read().strip() + ":" + ROOT + "/dev/terminal-stress/classes"

scenario = sys.argv[1] if len(sys.argv) > 1 else "band"
secs = sys.argv[2] if len(sys.argv) > 2 else "6"

master, slave = pty.openpty()
p = subprocess.Popen(
    ["java", "-cp", CP, "ScenarioStress", scenario, secs],
    stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
os.close(slave)

def drain(t=0.3):
    out = b""
    while True:
        r, _, _ = select.select([master], [], [], t)
        if not r:
            return out
        try:
            out += os.read(master, 65536)
        except OSError:
            return out

time.sleep(2.5)          # 等初始帧画完
drain(0.5)
os.write(master, "abc".encode())          # 打字
time.sleep(1.0)
os.write(master, "中文".encode())          # 中文（模拟 IME 上屏后的字节）
time.sleep(1.0)
echo_out = drain(0.5)
os.write(master, b"\x03")                  # Ctrl+C
time.sleep(0.8)
tail = drain(0.5)

text = (echo_out + tail).decode("utf-8", "replace")
print("scenario:", scenario)
print("echo 'abc' seen:", "abc" in text)
print("echo '中文' seen:", "中文" in text)
try:
    rc = p.wait(timeout=8)
except subprocess.TimeoutExpired:
    p.kill()
    rc = "TIMEOUT(still running)"
print("exit after Ctrl+C:", rc)
sys.exit(0 if (rc == 0 and "abc" in text) else 1)

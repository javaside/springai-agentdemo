#!/usr/bin/env python3
"""Stress event-driven rendering with concurrent PTY input and a 5,000-line SSE stream.

No network or real API key is used. Mutation-only environment switches prove
that the smoke turns red: CODETUI_FAIRNESS_MUTATE_IDLE=1 adds periodic idle PTY
bytes, while CODETUI_FAIRNESS_MUTATE_DROP_KEY=1 drops one scheduled edit.
"""
import importlib.util
import json
import os
import re
import shlex
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

ROWS, COLS = 45, 120
MODEL_ID = "deepseek-chat"
LINE_COUNT = 5000
LINE_TEMPLATE = "FAIR-%05d event-driven fairness payload"
FIRST_LINE = LINE_TEMPLATE % 1
LAST_LINE = LINE_TEMPLATE % LINE_COUNT
LINE_PATTERN = re.compile(rb"FAIR-(\d{5})")

# 750 ms is deliberately much larger than a normal local key-to-frame time
# (usually below 100 ms), while still low enough to catch a render drain that
# monopolizes the UI thread for several batches.  CI scheduling jitter needs
# more headroom than a single 100/150 ms render throttle interval.
MAX_INPUT_LATENCY_SECONDS = 0.750
INPUT_INTERVAL_SECONDS = 0.080
IDLE_OBSERVE_SECONDS = 2.200
TOTAL_TIMEOUT_SECONDS = 120.0


def _sse(payload):
    return ("data: " + json.dumps(payload) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "fairness-smoke-1", "object": "chat.completion.chunk", "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class StubModel(BaseHTTPRequestHandler):
    daemon_threads = True
    lines_sent = 0
    lock = threading.Lock()

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            self._write(_sse(_chunk({"role": "assistant", "content": ""})))
            for first in range(1, LINE_COUNT + 1, 25):
                last = min(first + 25, LINE_COUNT + 1)
                text = "".join((LINE_TEMPLATE % i) + "\n" for i in range(first, last))
                self._write(_sse(_chunk({"content": text})))
                with StubModel.lock:
                    StubModel.lines_sent = last - 1
                time.sleep(0.010)
            self._write(_sse(_chunk({}, finish="stop")))
            self._write(b"data: [DONE]\n\n")
        except (BrokenPipeError, ConnectionResetError):
            return

    def _write(self, data):
        self.wfile.write(data)
        self.wfile.flush()


def start_stub():
    StubModel.lines_sent = 0
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


def screen_has_edit(session, text, cursor_offset):
    for y, line in enumerate(session.screen.display):
        start = line.find(text)
        if start >= 0 and session.screen.cursor.y == y and session.screen.cursor.x == start + cursor_offset:
            return True
    return False


def wait_for_edit(session, expected, cursor_offset, deadline):
    while time.monotonic() < deadline:
        session.pump(0.010)
        if screen_has_edit(session, expected, cursor_offset):
            return time.monotonic()
        if session.proc.poll() is not None:
            rs.die("process exited while waiting for input edit %r" % expected,
                   list(session.screen.display))
    rs.die("timed out waiting for input edit %r at cursor offset %d" % (expected, cursor_offset),
           list(session.screen.display))


def wait_for_output_complete(session, started, deadline):
    while time.monotonic() < deadline:
        session.pump(0.050)
        numbers = {int(m.group(1)) for m in LINE_PATTERN.finditer(session.raw[started:])}
        if LINE_COUNT in numbers and len(numbers) == LINE_COUNT:
            return numbers
        if session.proc.poll() is not None:
            rs.die("process exited before model output completed", list(session.screen.display))
    numbers = {int(m.group(1)) for m in LINE_PATTERN.finditer(session.raw[started:])}
    missing = [i for i in range(1, LINE_COUNT + 1) if i not in numbers]
    rs.die("timed out waiting for complete output: got %d/%d, missing head=%r"
           % (len(numbers), LINE_COUNT, missing[:10]), list(session.screen.display))


def wait_raw_quiet(session, quiet, deadline):
    last_len = len(session.raw)
    quiet_since = time.monotonic()
    while time.monotonic() < deadline:
        session.pump(0.050)
        current = len(session.raw)
        if current != last_len:
            last_len = current
            quiet_since = time.monotonic()
        elif time.monotonic() - quiet_since >= quiet:
            return
        if session.proc.poll() is not None:
            rs.die("process exited while waiting for full quiescence", list(session.screen.display))
    rs.die("terminal never became byte-quiet for %.1fs" % quiet, list(session.screen.display))


def java_command(classpath, home, mutation_trigger):
    base = ["java", "-Duser.home=" + home, "-Dcodetui.hardwareCursor=always",
            "-cp", classpath, rs.MAIN_CLASS]
    if os.environ.get("CODETUI_FAIRNESS_MUTATE_IDLE") != "1":
        return base
    # Mutation-only genuine PTY output starts only after the test has observed
    # quiescence and cleared raw.  This proves the final assertion itself sees
    # terminal bytes, rather than failing earlier or comparing visible frames.
    java = " ".join(shlex.quote(part) for part in base)
    trigger = shlex.quote(mutation_trigger)
    writer = "(while [ ! -e %s ]; do sleep 0.05; done; while :; do printf '\\033[0m'; sleep 0.25; done)" % trigger
    return ["sh", "-c", writer + " & exec " + java]


def main():
    deadline = time.monotonic() + TOTAL_TIMEOUT_SECONDS
    classpath = rs.build_classpath()
    srv, base_url = start_stub()
    tmpdir = tempfile.mkdtemp(prefix="codetui-fairness-smoke-")
    home = os.path.join(tmpdir, "home")
    os.makedirs(home)
    env = dict(os.environ)
    env.update({
        "TERM": "xterm-256color",
        "DEEPSEEK_API_KEY": "sk-dummy-not-real",
        "DEEPSEEK_BASE_URL": base_url,
        "DEEPSEEK_MODELS": MODEL_ID,
    })
    for key in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
                "OPENCODE_GO_API_KEY", "BOCHA_API_KEY", "BRAVE_API_KEY"):
        env.pop(key, None)

    mutation_trigger = os.path.join(tmpdir, "emit-idle-mutation")
    cmd = java_command(classpath, home, mutation_trigger)
    print("Launching (%dx%d, total timeout %.0fs): %s" %
          (ROWS, COLS, TOTAL_TIMEOUT_SECONDS, " ".join(cmd)))
    session = rs.PtySession(cmd, tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(rs.WELCOME, timeout=min(40, max(1, deadline - time.monotonic())))
        session.wait_stable(quiet=0.6, timeout=8)
        session.write(b"print fairness lines\r")
        output_mark = len(session.raw)

        # Every tuple is (bytes, expected buffer, expected cursor offset).  It
        # covers ASCII, Backspace, Left and Right while preserving a unique,
        # ordered final value that detects swallowed or reordered keys.
        edits = [
            (b"a", "a", 1), (b"b", "ab", 2), (b"c", "abc", 3),
            (b"\x1b[D", "abc", 2), (b"X", "abXc", 3),
            (b"\x1b[C", "abXc", 4), (b"d", "abXcd", 5),
            (b"\x7f", "abXc", 4), (b"Z", "abXcZ", 5),
        ]
        observations = []
        first_edit_before_final = False
        next_send = time.monotonic()
        for edit_index, (data, expected, cursor_offset) in enumerate(edits):
            while time.monotonic() < next_send:
                session.pump(min(0.010, next_send - time.monotonic()))
            sent = time.monotonic()
            if not (os.environ.get("CODETUI_FAIRNESS_MUTATE_DROP_KEY") == "1" and edit_index == 1):
                session.write(data)
            seen = wait_for_edit(session, expected, cursor_offset,
                                 min(deadline, sent + MAX_INPUT_LATENCY_SECONDS))
            latency = seen - sent
            observations.append((data, expected, latency))
            if LAST_LINE.encode() not in session.raw[output_mark:]:
                first_edit_before_final = True
            next_send = sent + INPUT_INTERVAL_SECONDS

        final_expected = edits[-1][1]
        if not screen_has_edit(session, final_expected, edits[-1][2]):
            rs.die("final input model is not %r; key lost or reordered" % final_expected,
                   list(session.screen.display))
        max_latency = max(latency for _, _, latency in observations)
        if max_latency > MAX_INPUT_LATENCY_SECONDS:
            rs.die("max input latency %.3fs exceeded %.3fs threshold"
                   % (max_latency, MAX_INPUT_LATENCY_SECONDS), list(session.screen.display))
        if not first_edit_before_final:
            rs.die("no input edit became visible before the final model line",
                   list(session.screen.display))

        numbers = wait_for_output_complete(session, output_mark, deadline)
        expected_numbers = set(range(1, LINE_COUNT + 1))
        if numbers != expected_numbers:
            missing = sorted(expected_numbers - numbers)
            extra = sorted(numbers - expected_numbers)
            rs.die("model line sequence incomplete: missing=%r extra=%r" % (missing[:10], extra[:10]),
                   list(session.screen.display))
        with StubModel.lock:
            sent_count = StubModel.lines_sent
        if sent_count != LINE_COUNT:
            rs.die("SSE stub sent %d/%d lines" % (sent_count, LINE_COUNT),
                   list(session.screen.display))

        # Wait for completion/attention/IME on-demand follow-ups to finish, then
        # CLEAR the accumulator itself (not merely a mark into old bytes).
        wait_raw_quiet(session, 1.2, deadline)
        session.raw = b""
        if os.environ.get("CODETUI_FAIRNESS_MUTATE_IDLE") == "1":
            open(mutation_trigger, "w").close()
        session.pump(IDLE_OBSERVE_SECONDS)
        idle_bytes = len(session.raw)
        if idle_bytes:
            rs.die("idle %.1fs emitted %d new terminal bytes: %r"
                   % (IDLE_OBSERVE_SECONDS, idle_bytes, session.raw[:160]),
                   list(session.screen.display))

        print("INPUT OK: %d ordered edits, final=%r, max input latency %.3fs (threshold %.3fs)"
              % (len(observations), final_expected, max_latency, MAX_INPUT_LATENCY_SECONDS))
        print("OUTPUT OK: first=%s last=%s total=%d" % (FIRST_LINE, LAST_LINE, len(numbers)))
        print("IDLE OK: raw accumulator cleared; %.1fs produced %d terminal bytes"
              % (IDLE_OBSERVE_SECONDS, idle_bytes))
        session.write(b"\x15/exit\r")
        session.pump(1.0)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""PTY smoke test for MCP client wiring at real startup.

Launches the REAL code-tui app on a pseudo-terminal (reusing clear_smoke.py's
PtySession + classpath machinery) with a real stdio MCP server configured, and
asserts things that can only be verified by booting the actual application:

1. Boot with MCP does not crash: the startup welcome banner appears. This proves
   McpConfigLoader.load -> McpClientManager.connectAll -> toolCallbacks -> and
   AgentTools.build(..., mcpTools) all ran at real main() startup, spawning a
   real `npx @modelcontextprotocol/server-filesystem` child process and wiring
   its tools into the registry without any classloading / JSON-schema crash.
2. The startup notice "已发现 N 个工具" appears, proving the MCP server was
   actually connected and its tools discovered in the real app (not just in a
   unit test with a fake ToolCallback).
3. /exit terminates PROMPTLY (well under the ~60s non-daemon-thread hang the
   project has a history of). This guards the bounded mcpManager.close() before
   System.exit — a regression to unbounded close would show up as a slow exit.
4. No orphaned server-filesystem child process survives the exit, proving
   mcpManager.close() actually destroyed the stdio child.

Launched with a dummy API key (sk-dummy-not-real): the model never responds, so
a model-driven MCP tool *invocation* isn't exercised here (that is covered
separately by driving the ToolCallback directly against a real server). The
achievable end-to-end smoke is boot-with-MCP-without-crash + tools-discovered +
prompt-exit + no-orphan, and that is exactly what catches broken MCP wiring at
real startup.

Requires the module compiled and the classpath file present:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
Also requires `npx` on PATH (Node.js) and network/npm cache for the filesystem
server package.

Usage:
    /usr/bin/python3 scripts/mcp_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>" on
failure. Prints the last-seen screen snapshot on failure for a human to eyeball.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time

# Reuse clear_smoke.py's pty scaffolding verbatim. It guards main() behind
# `if __name__ == "__main__":`, so importing it does NOT run the /clear smoke.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    MAIN_CLASS,
    WELCOME_1,
    WELCOME_2,
)

MCP_TOOLS_NOTICE = "已发现"          # from CodeTuiApplication: "（MCP：已发现 N 个工具。）"
EXIT_BUDGET_SEC = 8.0                # generous; a healthy exit is ~1-3s, the hang regression was ~60s
ORPHAN_MARKER = "server-filesystem"  # child process command substring


def count_orphans():
    """Count live processes whose command line contains the filesystem server."""
    try:
        out = subprocess.run(
            ["pgrep", "-f", ORPHAN_MARKER],
            capture_output=True, text=True,
        )
        return len([l for l in out.stdout.splitlines() if l.strip()])
    except Exception:
        return -1  # pgrep unavailable -> skip orphan assertion


def main():
    if shutil.which("npx") is None:
        die("npx not on PATH; MCP smoke requires Node.js/npx")

    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-mcpsmoke-")

    # Write a real project-level MCP config pointing at the filesystem server.
    cfg_dir = os.path.join(tmpdir, ".codetui")
    os.makedirs(cfg_dir, exist_ok=True)
    with open(os.path.join(cfg_dir, "mcp.json"), "w") as f:
        json.dump({
            "mcpServers": {
                "filesystem": {
                    "command": "npx",
                    "args": ["-y", "@modelcontextprotocol/server-filesystem", tmpdir],
                }
            }
        }, f)

    baseline_orphans = count_orphans()

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    cmd = ["java", "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        # 1. Boot OK with MCP: welcome banner appears (MCP connect+wiring didn't crash).
        #    Longer timeout: real npx server spawn happens synchronously before the TUI.
        session.wait_for(WELCOME_1, timeout=40)
        text = session.screen_text()
        if WELCOME_2 not in text:
            die("startup welcome banner missing %r" % WELCOME_2, session.screen.display)
        print("Startup welcome banner OK (MCP wiring assembled, no crash).")

        # 2. MCP tools discovered notice.
        session.wait_for(MCP_TOOLS_NOTICE, timeout=15)
        print("MCP tools-discovered notice OK (%r present)." % MCP_TOOLS_NOTICE)

        # 3. /exit terminates promptly.
        session.write(b"/exit\r")
        t0 = time.time()
        while time.time() - t0 < EXIT_BUDGET_SEC:
            if session.proc.poll() is not None:
                break
            session.pump(0.2)
        exit_secs = time.time() - t0
        if session.proc.poll() is None:
            die("process did not exit within %.1fs after /exit (hang regression?)"
                % EXIT_BUDGET_SEC, session.screen.display)
        print("/exit terminated promptly in %.2fs." % exit_secs)

        # 4. No orphaned filesystem-server child process survives.
        time.sleep(1.0)  # let the OS reap
        if baseline_orphans >= 0:
            remaining = count_orphans()
            if remaining > baseline_orphans:
                die("orphaned %s process(es) survived exit: baseline=%d now=%d"
                    % (ORPHAN_MARKER, baseline_orphans, remaining), session.screen.display)
            print("No orphaned MCP child process (baseline=%d, now=%d)."
                  % (baseline_orphans, remaining))
        else:
            print("pgrep unavailable; skipped orphan-process assertion.")

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

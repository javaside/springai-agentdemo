#!/usr/bin/env python3
"""PTY smoke test for /mcp runtime management.

Boots the real app with one real stdio MCP server, then:
1. Opens /mcp -> asserts the panel lists the server as connected (mark + 已连接).
2. Presses Enter to disable -> asserts the row flips to 已禁用 AND the
   project mcp.json now has "enabled": false (persisted write-back).
3. Presses Enter again to re-enable -> asserts the row returns to 已连接 and
   mcp.json flips back to "enabled": true.
4. Esc closes the panel; /exit terminates promptly; no orphaned child.

pyte renders the real screen; unit tests cannot reach the real panel
rendering nor the real write-back path end-to-end.

Requires the module compiled and the classpath file present:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
Also requires `npx` on PATH (Node.js) and network/npm cache for the
filesystem server package.

Usage:
    /usr/bin/python3 scripts/mcp_manage_smoke.py
"""
import json
import os
import shutil
import sys
import tempfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
)
from mcp_smoke import count_orphans  # noqa: E402

PANEL_TITLE = "MCP 服务器"
CONNECTED = "已连接"
DISABLED = "已禁用"
PROJECT_LAYER = "[项目级]"
MCP_TOOLS_NOTICE = "已发现"
EXIT_BUDGET_SEC = 10.0
SERVER_NAME = "fs"


def read_enabled(cfg_path):
    with open(cfg_path) as f:
        entry = json.load(f)["mcpServers"][SERVER_NAME]
    return entry.get("enabled")  # None = key absent (treated as enabled)


def wait_enabled(cfg_path, expected, timeout=10):
    """Write-back happens on a background/UI thread; poll the file."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if read_enabled(cfg_path) is expected:
                return True
        except Exception:
            pass  # mid-write / tmp swap
        time.sleep(0.2)
    return False


def main():
    if shutil.which("npx") is None:
        die("npx not on PATH; MCP smoke requires Node.js/npx")

    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-mcpmanage-")
    # Isolate the user layer: without this the app also loads the REAL
    # ~/.codetui/mcp.json, whose servers pollute the panel and break the
    # row-index assumptions below (first row must be our "fs" entry).
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    cfg_dir = os.path.join(tmpdir, ".codetui")
    os.makedirs(cfg_dir, exist_ok=True)
    cfg_path = os.path.join(cfg_dir, "mcp.json")
    with open(cfg_path, "w") as f:
        json.dump({
            "mcpServers": {
                SERVER_NAME: {
                    "command": "npx",
                    "args": ["-y", "@modelcontextprotocol/server-filesystem", tmpdir],
                }
            }
        }, f)

    baseline_orphans = count_orphans()

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        # Boot: banner + tools-discovered notice (server really connected).
        session.wait_for(WELCOME_1, timeout=40)
        session.wait_for(MCP_TOOLS_NOTICE, timeout=15)
        print("Boot OK: MCP server connected, tools discovered.")

        # 1. Open the /mcp panel. "/mcp" uniquely matches in the slash menu,
        #    so a single Enter runs the command via onSlashMenuKey.
        session.write(b"/mcp\r")
        session.wait_for(PANEL_TITLE, timeout=10)
        session.pump(0.5)
        text = session.screen_text()
        if CONNECTED not in text:
            die("panel missing %r (server not shown connected)" % CONNECTED,
                session.screen.display)
        if PROJECT_LAYER not in text:
            die("panel missing source layer %r" % PROJECT_LAYER, session.screen.display)
        print("/mcp panel OK: server listed as connected, project layer tagged.")

        # 2. Enter -> disable. Row flips to 已禁用 and mcp.json persists false.
        session.write(b"\r")
        deadline = time.time() + 10
        while time.time() < deadline:
            session.pump(0.3)
            if DISABLED in session.screen_text():
                break
        if DISABLED not in session.screen_text():
            die("row did not flip to %r after disable" % DISABLED, session.screen.display)
        if not wait_enabled(cfg_path, False, timeout=10):
            die("mcp.json enabled was not persisted to false", session.screen.display)
        print("Disable OK: row flipped to 已禁用, mcp.json enabled=false persisted.")

        # 3. Enter -> re-enable. Real npx reconnect: allow up to 30s.
        session.write(b"\r")
        deadline = time.time() + 30
        while time.time() < deadline:
            session.pump(0.4)
            if CONNECTED in session.screen_text():
                break
        if CONNECTED not in session.screen_text():
            die("row did not return to %r after re-enable" % CONNECTED,
                session.screen.display)
        if not wait_enabled(cfg_path, True, timeout=10):
            die("mcp.json enabled was not persisted back to true", session.screen.display)
        print("Re-enable OK: row back to 已连接, mcp.json enabled=true persisted.")

        # 4. Esc closes the panel.
        session.write(b"\x1b")
        deadline = time.time() + 5
        while time.time() < deadline:
            session.pump(0.3)
            if PANEL_TITLE not in session.screen_text():
                break
        if PANEL_TITLE in session.screen_text():
            die("panel did not close on Esc", session.screen.display)
        print("Esc closed the panel.")

        # 5. /exit terminates promptly, no orphaned child.
        session.write(b"/exit\r")
        t0 = time.time()
        while time.time() - t0 < EXIT_BUDGET_SEC:
            if session.proc.poll() is not None:
                break
            session.pump(0.2)
        if session.proc.poll() is None:
            die("process did not exit within %.1fs after /exit" % EXIT_BUDGET_SEC,
                session.screen.display)
        print("/exit terminated promptly in %.2fs." % (time.time() - t0))

        time.sleep(1.0)
        if baseline_orphans >= 0:
            remaining = count_orphans()
            if remaining > baseline_orphans:
                die("orphaned server-filesystem process(es): baseline=%d now=%d"
                    % (baseline_orphans, remaining))
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

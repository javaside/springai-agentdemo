#!/usr/bin/env python3
"""PTY smoke test for long-term-memory tool wiring at real startup.

Launches the REAL code-tui app on a pseudo-terminal (reusing clear_smoke.py's
PtySession + classpath machinery) and asserts two things that can only be
verified by booting the actual application:

1. The app boots: the startup welcome banner appears. This proves the
   long-term-memory tools were assembled into the tool registry at the real
   main() startup without any classloading / JSON-schema crash.
2. The memory directory <cwd>/.codetui/memory is created during startup. This
   proves AutoMemoryTools was actually instantiated in the real app, because
   its build() auto-creates that directory. A unit test that never runs the
   real main() cannot catch a regression that only breaks the assembled app.

Then it exits cleanly via /exit.

Because the app is launched with a dummy API key (sk-dummy-not-real), the model
never responds, so a real memory-tool *invocation* can't be driven here. The
achievable end-to-end smoke is boot-without-crash + dir-creation, and that is
exactly what catches a broken memory wiring at real startup.

Requires the module compiled and the classpath file present (same as
clear_smoke.py):
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt   # if target/cp.txt is missing

Usage:
    /usr/bin/python3 scripts/memory_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>" on
failure. Always prints the last-seen screen snapshot on failure for a human to
eyeball.
"""
import os
import sys
import tempfile

# Reuse clear_smoke.py's pty scaffolding verbatim. It guards main() behind
# `if __name__ == "__main__":`, so importing it does NOT run the /clear smoke.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
    WELCOME_2,
    WELCOME_3,
)


def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-memsmoke-")

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    cmd = ["java", "-cp", classpath, MAIN_CLASS]

    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        # 1. Boot OK: welcome banner appears -> memory tools assembled without crash.
        session.wait_for(WELCOME_1, timeout=20)
        text = session.screen_text()
        if WELCOME_2 not in text or WELCOME_3 not in text:
            die(
                "startup welcome banner missing expected substrings (%r / %r)"
                % (WELCOME_2, WELCOME_3),
                session.screen.display,
            )
        print("Startup welcome banner OK (memory tools assembled, no crash).")

        # 2. AutoMemoryTools.build() auto-creates <cwd>/.codetui/memory at startup.
        memory_dir = os.path.join(tmpdir, ".codetui", "memory")
        if not os.path.isdir(memory_dir):
            die(
                "memory directory not created at startup: %s "
                "(AutoMemoryTools not instantiated in real app?)" % memory_dir,
                session.screen.display,
            )
        print("Memory directory created OK: %s" % memory_dir)

        # 3. Clean shutdown.
        session.write(b"/exit\r")
        session.pump(1.0)

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

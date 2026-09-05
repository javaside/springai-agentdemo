#!/usr/bin/env python3
"""§5.6 变异纪律：逐条改坏，确认有测试变红。跑完自动还原（含 touch，否则 Maven 跳过重编译）。

最近一次结果（2026-09-05）：15 条变异全部变红，未被守住 0 条。

两个踩过的坑，改这份脚本时别再掉进去：

- **锚点要落在真正会执行的那一份**。`if (output.size() >= MAX_OUTPUT_LINES)` 在
  MarkdownTable.render 里有三份（表头 / 分隔线 / 数据行），`replace(..., 1)` 只会改到表头
  那份——表头永远一两行、上限根本不触发，于是整条变异全绿。那是脚本的假绿，不是测试的漏洞。
  改常量（`= Integer.MAX_VALUE`）才等价于「上限没了」。
- **全绿不等于没守住，先查那条测试是不是为错误的理由通过的**。600 行上限原来的用例用
  `render(block, 4)`，而 `inner < 6` 会先退回原样：它测的是宽度守卫，不是上限。

用法：/usr/bin/python3 dev/table_mutation_check.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui")

VIEW = os.path.join(UI, "CodeTuiView.java")
PRINTER = os.path.join(UI, "ScrollbackPrinter.java")
RENDERER = os.path.join(UI, "MarkdownRenderer.java")
TABLE = os.path.join(UI, "MarkdownTable.java")

TESTS = "MarkdownTableTest,MarkdownRendererTableTest,ScrollbackPrinterTest,CodeTuiViewTableFlushTest"

# (名字, 文件, 原文片段, 替换成, 期望变红的用例名关键字)
MUTATIONS = [
    ("第2条 flush 改成条件化入队", VIEW,
     "                    outputQueue.enqueue(v -> printer.tableFlushCursor());\n            default -> { /* ASSISTANT",
     "                    { if (printer.hasBufferedTable()) outputQueue.enqueue(v -> printer.tableFlushCursor()); }\n            default -> { /* ASSISTANT",
     "toolStart"),
    ("第2条 flush 整条去掉", VIEW,
     "            case USER, TOOL_START, TOOL_OK, TOOL_FAIL, ERROR,\n                 SUBAGENT_START, SUBAGENT_TOOL, SUBAGENT_END, TODO ->",
     "            case TODO ->",
     "toolStart"),
    ("ERROR 也放进豁免", VIEW,
     "            case USER, TOOL_START, TOOL_OK, TOOL_FAIL, ERROR,",
     "            case USER, TOOL_START, TOOL_OK, TOOL_FAIL,",
     "error"),
    ("第4条 兜底 flush 整条注掉", VIEW,
     "        if (!localWorkRemaining && (state.isIdle() || state.hasModal()) && printer.hasBufferedTable()) {",
     "        if (false) {",
     "turnEnding"),
    ("第4条 只删「输入已排空」那一半", VIEW,
     "        if (!localWorkRemaining && (state.isIdle() || state.hasModal()) && printer.hasBufferedTable()) {",
     "        if ((state.isIdle() || state.hasModal()) && printer.hasBufferedTable()) {",
     "InputRemains"),
    ("第5条 printPlan 收尾 flush 去掉", VIEW,
     "        outputQueue.enqueue(v -> printer.tableFlushCursor());\n    }\n\n    /**\n     * 计划面板",
     "    }\n\n    /**\n     * 计划面板",
     "plan"),
    ("第6条 /clear 不复位状态机", VIEW,
     "            printer.resetMarkdown();",
     "            /* 变异：不复位 */",
     "clear"),
    ("重投喂去掉（候选行吃掉真表格）", RENDERER,
     "                        out.add(renderFinalized(candidate));\n                        reFeed = current;",
     "                        out.add(renderFinalized(candidate));",
     "reFeed"),
    ("next() 内部循环改成单次 feed", PRINTER,
     "                    while (pendingOutput.isEmpty() && at < logicals.length) {\n                        pendingOutput.addAll(md.feed(logicals[at++], innerWidth()));\n                    }",
     "                    if (pendingOutput.isEmpty() && at < logicals.length) {\n                        pendingOutput.addAll(md.feed(logicals[at++], innerWidth()));\n                    }",
     "200Row"),
    ("续段优先改回「先推进」", PRINTER,
     "                if (segs == null || !segs.hasNextSegment()) {\n                    // feed 返回空列表",
     "                if (true) {\n                    // feed 返回空列表",
     "wrappedLine"),
    # ⚠ 别锚 `if (output.size() >= MAX_OUTPUT_LINES)`：这段在表头循环 / 分隔线 / 数据行循环里
    # 各有一份，replace(..., 1) 只会改到<b>表头</b>那份（表头永远只有一两行、上限根本不触发），
    # 于是整条变异全绿——是脚本的假绿，不是测试的漏洞。改常量才等价于「上限没了」。
    ("产出侧 600 行上限去掉", TABLE,
     "        final int MAX_OUTPUT_LINES = 600;",
     "        final int MAX_OUTPUT_LINES = Integer.MAX_VALUE;",
     "600"),
    ("displayWidth 改回自造 CJK 区间表", TABLE,
     "        return CharWidth.of(s);",
     "        int w = 0;\n        for (int i = 0; i < s.length(); i++) {\n            int cp = s.codePointAt(i);\n            if (Character.isSupplementaryCodePoint(cp)) i++;\n            w += (cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0xFF00 && cp <= 0xFFEF) ? 2 : 1;\n        }\n        return w;",
     "CharWidth"),
    ("缓冲上限去掉（不再降级）", RENDERER,
     "        if (tableBuffer.size() <= MAX_BUFFERED_ROWS && bufferedCharCount <= MAX_BUFFERED_CHARS) {",
     "        if (true) {",
     "degrade"),
    ("围栏守卫去掉", RENDERER,
     "                    if (!inCodeBlock && MarkdownTable.looksLikeRow(current)) {",
     "                    if (MarkdownTable.looksLikeRow(current)) {",
     "Fenced"),
    ("parseCells 回到「丢掉所有空格子」", TABLE,
     "        int from = 0;\n        int to = cells.size();",
     "        cells.removeIf(String::isEmpty);\n        int from = 0;\n        int to = cells.size();",
     "InteriorEmpty"),
]


def run_tests():
    p = subprocess.run(["mvn", "-pl", "springai-code-tui", "test", "-Dtest=" + TESTS],
                       cwd=ROOT, capture_output=True, text=True)
    failed = []
    for line in p.stdout.splitlines():
        if line.startswith("[ERROR]   ") and "." in line:
            failed.append(line[len("[ERROR]   "):].strip())
    compile_error = "COMPILATION ERROR" in p.stdout
    return p.returncode, failed, compile_error


def main():
    backups = tempfile.mkdtemp(prefix="mutation-backup-")
    for path in {VIEW, PRINTER, RENDERER, TABLE}:
        shutil.copy2(path, os.path.join(backups, os.path.basename(path)))

    print("基线：跑一遍确认全绿")
    code, failed, _ = run_tests()
    if code != 0:
        print("基线就不绿，先修：", failed)
        return 1
    print("基线 OK\n")

    results = []
    try:
        for name, path, old, new, expect in MUTATIONS:
            src = open(path, encoding="utf-8").read()
            if old not in src:
                results.append((name, "SKIP", "锚点没匹配上（代码已变，变异脚本要跟着改）"))
                print("!! %-38s 锚点没匹配上" % name)
                continue
            open(path, "w", encoding="utf-8").write(src.replace(old, new, 1))
            os.utime(path, None)
            code, failed, compile_error = run_tests()
            # 还原 + touch：mv/cp 保留旧 mtime，Maven 会跳过重编译、跑的还是变异 class
            shutil.copy2(os.path.join(backups, os.path.basename(path)), path)
            os.utime(path, None)

            if compile_error:
                results.append((name, "COMPILE", "变异让代码编译不过，等价于被抓住"))
                print("OK %-38s 编译失败（变异不成立）" % name)
            elif code == 0:
                results.append((name, "GREEN", "没有测试变红——这条变异没人守"))
                print("!! %-38s 全绿：没有测试守住" % name)
            else:
                hit = [f for f in failed if expect.lower() in f.lower()]
                tag = "RED" if hit else "RED-OTHER"
                results.append((name, tag, "; ".join(failed[:3])))
                print("OK %-38s 变红 %d 条%s" % (name, len(failed),
                                                "" if hit else "（但不是预期用例）"))
    finally:
        for path in {VIEW, PRINTER, RENDERER, TABLE}:
            shutil.copy2(os.path.join(backups, os.path.basename(path)), path)
            os.utime(path, None)
        time.sleep(0.1)

    print("\n==== 汇总 ====")
    for name, tag, detail in results:
        print("%-10s %-38s %s" % (tag, name, detail[:110]))
    unguarded = [r for r in results if r[1] in ("GREEN", "SKIP")]
    print("\n未被守住/跳过：%d 条" % len(unguarded))
    return 1 if unguarded else 0


if __name__ == "__main__":
    sys.exit(main())

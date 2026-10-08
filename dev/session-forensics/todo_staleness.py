#!/usr/bin/env python3
"""会话取证：TodoWrite 清单「建了不更新」的跨语料统计（根因分析的可复核证据源）。

分析对象是 code-tui 持久化的会话 JSON（`<项目>/.codetui/sessions/*.json`，`events` 数组
与实际发给模型的消息一一对应）。脚本只读文件，不改任何东西。

三个指标（定义与文档口径必须逐字一致，改这里就要改文档）：

- **实质工具调用**：`toolCalls` 里的全部调用，剔除 `BashOutput` / `TaskOutput`。
  这两个是轮询等待（一次 mvn 全量测试能刷出几十次），不是任务推进；不剔除的话
  任何长构建都会把「冻结跨度」虚报成几十。
- **冻结跨度（maxstale）**：清单处于「存在未完成项」状态期间，连续实质工具调用数的最大值。
  它衡量「面板冻了多久」，不衡量「模型错没错」——清单合法停在 in_progress 也会计入。
- **提醒注入与响应率**：工具返回文本尾部被注入 `[任务面板提醒]` 即计一次注入；注入后 3 次
  实质工具调用内出现 TodoWrite 即视为被采纳。这是「干预是否真的起作用」的唯一字段指标，
  功能未发布时恒为 0（作为上线后的回填位）。
- **完成标记配对率（pairing）**：控制器自己做出「把一个工作单元记为完成」的动作时
  （Bash 写入 ledger/progress 台账，或 Bash 含 `git commit`），此刻清单仍有未完成项，
  该事件或紧接着的下一个工具调用事件里出现 TodoWrite 即算配对。这是「顺手更新」的直接度量。
  台账判定覆盖三种真实形态：`cat >> …/progress.md << 'EOF'`、`echo … >> progress.md`
  这类重定向，以及 python 内联脚本 `s += …` 写回 progress.md。只看文件名会把
  `cat progress.md`（读回台账）也算成完成动作；只匹配英文 `complete` 会漏掉中文表述
  （F 会话最后一次台账写的是「全部 7 任务…完成」）。`git commit` 须出现在行首或命令
  分隔符之后，包在引号里的（`bash -c "git commit …"`）不计。

已知口径边界（写进结论时必须一起说，否则数字会被过度解读）：

- 分区只有「本会话出现过 `Skill(subagent-driven-development)`」这一条。SDD 会话里
  规划期同样计入冻结统计，因此该分区是保守的。
- 配对率只统计**做出完成标记那一刻**的顺手更新，不统计中途的提前勾选。
- 会话是用户真实工作留下的，不是对照实验：样本量、任务形态、模型/配置都在变。
- 分析脚本自身的命令被排除（其命令行里就含这些判定字面量），否则每次跑分析都在污染样本。
- 会话目录是活数据，重跑会有小幅漂移；文档中的数字是某一次运行的快照。

用法：

    /usr/bin/python3 dev/session-forensics/todo_staleness.py            # 自动扫本机全部会话根
    /usr/bin/python3 dev/session-forensics/todo_staleness.py DIR [DIR…] # 指定目录

最近一次结果（2026-10-08，322 个会话 / 184 个建过清单 / 14 个 SDD，519 个完成标记）：
配对率非 SDD 0.63 vs SDD 0.37；冻结跨度 ≥50 且有完成标记的会话 23 个（非 SDD 20、SDD 3）；
装载前 TodoWrite 次数与装载后配对率无关联（0 次组 0.28 vs ≥1 次组 0.41，组内跨满量程）。

口径修订留痕（2026-10-08）：commit 识别原按 shell 语义锚定行首/分隔符，漏计了 30 次
命令行开头就写 `git commit` 的提交（Bash 入参是 JSON，前面跟的是引号）；改为词边界后
标记 505→519、配对 288→299、非 SDD 0.63 不变。结论方向不变，绝对计数更正。
完整表格与解读见 docs/superpowers/specs/2026-10-07-todowrite-not-updated-root-cause.md。
"""
import glob
import json
import os
import re
import statistics
import sys

POLLING = {"BashOutput", "TaskOutput"}
TASK_TOOLS = {"Task", "ParallelTasks"}


def discover_roots():
    """本机全部会话根：~/IdeaProjects/*/.codetui/sessions + ~/.codetui/sessions。"""
    home = os.path.expanduser("~")
    roots = sorted(glob.glob(os.path.join(home, "IdeaProjects", "*", ".codetui", "sessions")))
    legacy = os.path.join(home, ".codetui", "sessions")
    if os.path.isdir(legacy):
        roots.append(legacy)
    return roots


def todo_items(arguments):
    """TodoWrite 入参里的 todos 列表；解析失败返回 None（该次调用只用于计数）。"""
    try:
        obj = json.loads(arguments) if isinstance(arguments, str) else arguments
        items = obj.get("todos") if isinstance(obj, dict) else None
        return items if isinstance(items, list) else None
    except (ValueError, TypeError):
        return None


def has_unfinished(items):
    if not items:
        return False
    return any(isinstance(t, dict) and t.get("status") != "completed" for t in items)


# 写入重定向符后紧跟台账路径（可带引号）；`cat >> <abs>/progress.md << 'EOF'` 与 `echo x >> progress.md` 都命中
LEDGER_WRITE = re.compile(r"(?:>>?|tee\s+-a)\s*[\"']?\S*(?:progress\.md|ledger)", re.I)
# 用词边界而非「行首/分隔符」锚定：入参是 Bash 的 arguments JSON（{"command":"…"}），
# 命令行开头的 git 前面跟的是引号。早期按 shell 语义锚定，导致「git commit -m x」这种最自然的写法
# 被漏计——实测全语料有 30 次提交属于该形态（放宽后总命中 721）。与 TodoStaleReminder.COMMIT 同一口径。
# 另：也允许 git 与子命令之间夹全局选项（git -C <dir> commit / git -c k=v commit）。
COMMIT = re.compile(
    r"(?<![\w-])git\s+(?:(?:-[A-Za-z]|--[A-Za-z][A-Za-z-]*)(?:=\S+|\s+\S+)?\s+)*commit\b")

# 提醒器注入的标记与收尾（TodoStaleReminder.render 的两个锚点）。
# 收尾句用来区分「真注入」与「读到了含该文本的文档」：注入是追加在工具结果<b>尾部</b>的，
# 读文档时后面还会有别的内容。这个判据不完美（文档末尾恰好就是这句时会误计），故计数只作趋势用。
REMINDER_MARKER = "[任务面板提醒]"
REMINDER_TAIL = "再继续当前工作。"


def reminder_injected(response_data):
    """该工具返回文本是不是被注入过任务面板提醒。"""
    if not response_data or REMINDER_MARKER not in response_data:
        return False
    return response_data.rstrip().endswith(REMINDER_TAIL)


def is_bookkeeping(command):
    """台账写入：写入重定向符后紧跟 ledger/progress 路径。

    两个踩过的坑：

    - 只看文件名会把 `cat progress.md`（读回台账）也算成完成动作——本项目的真实形态是
      `cat >> …/progress.md << 'EOF'`，所以要求文件名紧跟在 `>>`/`>`/`tee -a` 之后。
    - 只匹配英文 `complete` 会漏：F 会话最后一次台账写的是中文「全部 7 任务…完成」，
      漏掉它会让「8 次台账」这一实测事实对不上。
    """
    if LEDGER_WRITE.search(command):
        return True
    # 另一种真实形态：python3 内联脚本把 "Task N: complete" 追加回 progress.md（实测
    # 20260911T154059-213a8e 全程如此）。要求「台账路径」与「写入语义」同现，
    # 避免把「读回台账」或「讨论台账的分析脚本」算成完成动作。
    low = command.lower()
    if "progress.md" not in low and "ledger" not in low:
        return False
    return "s +=" in command or "s+=" in command or ".write(" in command


def is_commit(command):
    """`git commit` 出现在行首或命令分隔符之后（`cd X && git commit -m …` 命中）。"""
    return bool(COMMIT.search(command))


def analyze(path):
    try:
        with open(path, encoding="utf-8") as fh:
            events = (json.load(fh).get("events") or [])
    except (ValueError, OSError):
        return None

    # 先定位 SDD 装载事件（必须在统计前定盘：装载点之前的更新次数只有知道装载点才能归位）
    tool_events = [(i, e.get("toolCalls") or []) for i, e in enumerate(events)]
    tool_events = [(i, calls) for i, calls in tool_events if calls]
    sdd_event = next(
        (i for i, calls in tool_events
         if any(c.get("name") == "Skill" and "subagent-driven" in (c.get("arguments") or "")
                for c in calls)),
        None,
    )

    items = None          # 最近一次 TodoWrite 的清单
    substantive = 0       # 实质工具调用总数
    injects = 0           # 本会话被注入提醒的次数
    inject_responded = 0  # 其中「注入后 3 次工具调用内出现 TodoWrite」的次数
    pending_inject = None # 待判定的注入发生位置（实质调用序号）
    todos_before_sdd = 0
    todos_after_sdd = 0
    tasks_after_sdd = 0
    max_stale = 0
    run = 0               # 当前连续未更新跨度
    marks = pair = 0      # 完成标记数 / 其中配对了 TodoWrite 的
    commits_while_stale = 0

    for ev_index, calls in tool_events:
        # 同一 assistant 消息里的多个 toolCall 视为一个事件：SDD 惯用「Bash 记账 + TodoWrite」同发。
        names = [c.get("name") for c in calls]
        wrote_todo = "TodoWrite" in names
        after_sdd = sdd_event is not None and ev_index > sdd_event
        if wrote_todo:
            if after_sdd:
                todos_after_sdd += 1
            elif sdd_event is not None:
                todos_before_sdd += 1

        is_mark = any(is_completion_mark(n, c) for n, c in zip(names, calls))
        injected = any(reminder_injected(tr.get("responseData"))
                       for tr in (events[ev_index].get("toolResponses") or []))

        for call in calls:
            if call.get("name") in POLLING:
                continue
            substantive += 1
            if after_sdd and call.get("name") in TASK_TOOLS:
                tasks_after_sdd += 1
            if call.get("name") == "TodoWrite":
                got = todo_items(call.get("arguments"))
                if got is not None:
                    items = got
                # 注入后 3 次实质调用内出现更新 = 提醒被采纳（响应率，衡量干预是否真的起作用）
                if pending_inject is not None and substantive - pending_inject <= 3:
                    inject_responded += 1
                pending_inject = None
                # TodoWrite 自身不推进任务、也不是「未更新」的证据
                continue
            if has_unfinished(items):
                run += 1
                max_stale = max(max_stale, run)
                if is_completion_mark(call.get("name"), call) and is_commit(call.get("arguments") or ""):
                    commits_while_stale += 1
        if injected:
            injects += 1
            pending_inject = substantive
        if wrote_todo:
            run = 0
        if is_mark and has_unfinished(items):
            marks += 1
            pair += 1 if (wrote_todo or _next_event_writes_todo(tool_events, ev_index)) else 0

    return dict(
        path=path,
        todo_calls=sum(1 for e in events for c in (e.get("toolCalls") or [])
                       if c.get("name") == "TodoWrite"),
        sdd=sdd_event is not None, substantive=substantive, max_stale=max_stale,
        injects=injects, inject_responded=inject_responded,
        marks=marks, pair=pair, commits_while_stale=commits_while_stale,
        todos_before_sdd=todos_before_sdd, todos_after_sdd=todos_after_sdd,
        tasks_after_sdd=tasks_after_sdd,
    )


def _next_event_writes_todo(tool_events, ev_index):
    """标记事件之后最近的工具调用事件里有没有 TodoWrite（同一消息算「顺手」的下限是下一个事件）。"""
    for index, calls in tool_events:
        if index > ev_index:
            return any(c.get("name") == "TodoWrite" for c in calls)
    return False


def short_store(store):
    """会话根缩成项目名（`~/IdeaProjects/<项目>/.codetui/sessions` → `<项目>`），便于文档逐行核对。"""
    parts = store.rstrip(os.sep).split(os.sep)
    return parts[-3] if parts[-1] == "sessions" and len(parts) >= 3 else store


def is_completion_mark(name, call):
    """这次工具调用是不是一个「完成标记」（台账写入或提交）。

    排除分析脚本自身的命令：本文件的命令行里就含 `progress.md`、`git commit` 这些判定
    字面量，不排除的话，每跑一次分析都会把自己这个会话算出一个标记，样本自我污染。
    """
    if name != "Bash":
        return False
    args = call.get("arguments") or ""
    if "session-forensics" in args:
        return False
    return is_bookkeeping(args) or is_commit(args)


def collect(roots):
    rows = []
    for root in roots:
        for path in sorted(glob.glob(os.path.join(root, "*.json"))):
            row = analyze(path)
            if row:
                row["store"] = root
                rows.append(row)
    return rows


def rate(rows):
    marks = sum(r["marks"] for r in rows)
    pair = sum(r["pair"] for r in rows)
    return marks, pair, (pair / marks if marks else float("nan"))


def main():
    roots = sys.argv[1:] or discover_roots()
    rows = collect(roots)
    with_todo = [r for r in rows if r["todo_calls"] > 0]
    sdd = [r for r in with_todo if r["sdd"]]
    plain = [r for r in with_todo if not r["sdd"]]

    print(f"会话总数 {len(rows)}；建过清单 {len(with_todo)}；其中 SDD {len(sdd)}\n")

    print("## 完成标记配对率\n")
    print(f"{'分组':12s} {'会话':>4s} {'标记数':>6s} {'配对':>5s} {'加权':>6s} {'中位':>6s}")
    for name, group in (("全部", with_todo), ("SDD", sdd), ("非 SDD", plain)):
        marks, pair, w = rate(group)
        rates = [r["pair"] / r["marks"] for r in group if r["marks"] >= 2]
        med = statistics.median(rates) if rates else float("nan")
        print(f"{name:12s} {len(group):4d} {marks:6d} {pair:5d} {w:6.2f} {med:6.2f}")

    print("\n## 冻结跨度 Top 15（含 store，便于文档逐行核对）\n")
    print(f"{'会话':38s} {'项目':26s} {'SDD':5s} {'实质调用':>8s} {'跨度':>5s} {'标记':>5s} {'配对':>5s} {'提交时冻结':>10s}")
    for r in sorted(with_todo, key=lambda x: -x["max_stale"])[:15]:
        print(f"{os.path.basename(r['path']):38s} {short_store(r['store']):26s} {str(r['sdd']):5s} "
              f"{r['substantive']:8d} {r['max_stale']:5d} {r['marks']:5d} {r['pair']:5d} "
              f"{r['commits_while_stale']:10d}")

    print("\n## SDD 会话：装载前的更新次数 vs 装载后的配对率\n")
    print(f"{'会话':38s} {'项目':26s} {'SDD前TodoWrite':>13s} {'SDD后TodoWrite':>13s} "
          f"{'SDD后Task':>9s} {'标记':>5s} {'配对':>5s} {'跨度':>5s}")
    for r in sorted(sdd, key=lambda x: x["todos_before_sdd"]):
        print(f"{os.path.basename(r['path']):38s} {short_store(r['store']):26s} "
              f"{r['todos_before_sdd']:13d} {r['todos_after_sdd']:13d} "
              f"{r['tasks_after_sdd']:9d} {r['marks']:5d} {r['pair']:5d} {r['max_stale']:5d}")

    print("\n## 冻结跨度分布（建过清单的会话）\n")
    print(f"{'分组':10s} {'≥100':>5s} {'50-99':>6s} {'20-49':>6s} {'<20':>5s} {'中位':>5s}")
    for name, group in (("全部", with_todo), ("SDD", sdd), ("非 SDD", plain)):
        spans = [r["max_stale"] for r in group]
        band = lambda lo, hi: len([s for s in spans if lo <= s <= hi])
        print(f"{name:10s} {band(100, 10**9):5d} {band(50, 99):6d} {band(20, 49):6d} "
              f"{band(0, 19):5d} {statistics.median(spans):5.0f}")

    print("\n## 冻结跨度 ≥50 且有完成标记的会话（冻结期间照样在提交/记账）\n")
    bad = [r for r in with_todo if r["max_stale"] >= 50 and r["marks"] > 0]
    print(f"共 {len(bad)} 个：SDD {len([r for r in bad if r['sdd']])}，非 SDD {len([r for r in bad if not r['sdd']])}")
    print(f"{'会话':38s} {'项目':26s} {'SDD':5s} {'跨度':>5s} {'标记':>5s} {'配对':>5s} {'提交时冻结':>10s}")
    for r in sorted(bad, key=lambda x: -x["max_stale"])[:12]:
        print(f"{os.path.basename(r['path']):38s} {short_store(r['store']):26s} {str(r['sdd']):5s} "
              f"{r['max_stale']:5d} {r['marks']:5d} {r['pair']:5d} {r['commits_while_stale']:10d}")

    # 干预有效性：提醒注入的次数与「注入后 3 次调用内更新」的比例。功能未发布时注入恒为 0，
    # 这一节作为上线后的回填位——上线判据就是它：响应率显著 > 0 才算干预有效，否则只剩运行时强制一条路。
    print("\n## 提醒注入与响应率（P0 上线后的验收指标；未发布时恒为 0）\n")
    injected = [r for r in with_todo if r.get("injects")]
    total_inj = sum(r["injects"] for r in injected)
    total_resp = sum(r["inject_responded"] for r in injected)
    rate_txt = f"{total_resp / total_inj:.2f}" if total_inj else "n/a（尚无注入）"
    print(f"注入总数 {total_inj}，其中 3 次调用内出现 TodoWrite {total_resp} 次，响应率 {rate_txt}")
    for r in injected:
        print(f"  {os.path.basename(r['path']):38s} {short_store(r['store']):26s} "
              f"注入 {r['injects']:3d}，响应 {r['inject_responded']:3d}")

    # v6 把「装载前有无更新节奏」当区分变量，这里按该二分口径直接算：二分后差异应显著，否则该变量不成立
    print("\n## 同口径二分：装载前有/无 TodoWrite 节奏，装载后的配对率\n")
    for name, group in (("装载前 0 次", [r for r in sdd if r["todos_before_sdd"] == 0]),
                        ("装载前 ≥1 次", [r for r in sdd if r["todos_before_sdd"] > 0])):
        marks, pair, w = rate(group)
        per = ", ".join(f"{r['pair']}/{r['marks']}" for r in sorted(group, key=lambda x: -x["pair"] / max(x["marks"], 1)))
        print(f"{name}：{len(group)} 会话，标记 {marks}，配对 {pair}，加权 {w:.2f}；各会话 {per}")


if __name__ == "__main__":
    main()

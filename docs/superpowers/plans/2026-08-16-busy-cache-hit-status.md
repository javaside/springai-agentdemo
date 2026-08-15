# Busy Cache Hit Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the current session cache hit percentage while the TUI is thinking or running a tool.

**Architecture:** Add a focused `ContextUsage.cacheHitSuffix()` projection over the existing throttled `ContextStats` snapshot. Append that projection to the `THINKING` and `RUNNING_TOOL` status suffixes; keep `IDLE`, compaction, menus, and draining-subagent status behavior unchanged. Existing tool-summary fitting will budget against the expanded suffix.

**Tech Stack:** Java 21, JUnit 5, Maven, tamboui off-screen rendering tests

## Global Constraints

- Busy states show only ` · 缓存命中 N%`, not the context-window percentage.
- The displayed value remains the latest refreshed, completed-usage session aggregate.
- Empty cache-hit data produces an empty string and no dangling separator.
- Compaction, skill selection, slash menu, and draining-subagent status lines remain unchanged.
- At 80 columns, a long tool summary yields space to both cache hit percentage and `Esc 取消`.

---

### Task 1: Render Cache Hit Percentage in Busy Status Lines

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java:91-103`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:3041-3053`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/BusyCacheHitStatusTest.java`

**Interfaces:**
- Consumes: `ContextUsage.cached`, refreshed by `ContextUsage.refresh()`; `ContextStats.cacheHitPercent()`.
- Produces: package-private `String ContextUsage.cacheHitSuffix()`, returning either `" · 缓存命中 N%"` or `""`.

- [ ] **Step 1: Write failing off-screen rendering tests**

Create `BusyCacheHitStatusTest` with a `SubmitHandler` stub that returns a `ContextStats` containing `events > 0`, `cacheHitPercent = 78`, and a model label. Construct a view, add one user event, call `ctxUsageForTest().refresh()`, and assert:

```java
@Test
void thinkingShowsCacheHit(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    state.onTurnStarted(1L);

    String screen = ViewScreen.of(view, 80);
    assertTrue(screen.contains("思考中"), screen);
    assertTrue(screen.contains("缓存命中 78%"), screen);
    assertFalse(screen.contains("上下文 78%"), screen);
}

@Test
void runningToolFitsCacheHitAndCancelAt80Columns(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    state.onTurnStarted(1L);
    state.onToolStarted(1L, "Task", "{\"prompt\":\"a very long delegated task prompt that fills the status line and must shrink\"}");

    String screen = ViewScreen.of(view, 80);
    assertTrue(screen.contains("运行 Task"), screen);
    assertTrue(screen.contains("缓存命中 78%"), screen);
    assertTrue(screen.contains("Esc 取消"), screen);
    assertFalse(screen.contains("上下文 78%"), screen);
}

@Test
void busyStatesOmitCacheSegmentWithoutUsage(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithoutCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    state.onTurnStarted(1L);

    String thinking = ViewScreen.of(view, 80);
    assertFalse(thinking.contains("缓存命中"), thinking);
    assertFalse(thinking.contains("·  ·"), thinking);

    state.onToolStarted(1L, "Read", "{\"filePath\":\"/tmp/a\"}");
    String running = ViewScreen.of(view, 80);
    assertFalse(running.contains("缓存命中"), running);
    assertFalse(running.contains("·  ·"), running);
}
```

The stub's cached snapshot must use the existing 15-argument `ContextStats` constructor, with cache values `120_832L`, `155_184L`, and `78`; the no-usage variant uses `0L`, `0L`, and `null`.

- [ ] **Step 2: Run the new tests and verify red state**

Run:

```bash
./mvnw -pl springai-code-tui -am -Dtest=BusyCacheHitStatusTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: `thinkingShowsCacheHit` and `runningToolFitsCacheHitAndCancelAt80Columns` fail because busy status suffixes do not contain `缓存命中 78%`; the no-usage test passes.

- [ ] **Step 3: Add the focused cache-hit suffix projection**

In `ContextUsage`, add:

```java
/** 忙碌态状态栏只需缓存命中率；无计费输入时不追加任何分隔符。 */
String cacheHitSuffix() {
    ContextStats s = cached;
    if (s == null || s.events() == 0 || s.cacheHitPercent() == null) return "";
    return " · 缓存命中 " + s.cacheHitPercent() + "%";
}
```

Keep `suffix()` behavior unchanged so the idle line still contains both context usage and cache hit percentage.

- [ ] **Step 4: Append cache hit suffix to thinking and tool states**

In `CodeTuiView.statusLine()`, compute the busy cache projection once immediately before the status switch and include it before action hints:

```java
String cacheHit = ctxUsage.cacheHitSuffix();
return switch (state.status()) {
    case IDLE -> {
        String hint = idleHint(statusModelLabel(), ctxUsage.suffix() + backgroundStatusSuffix());
        yield mode == null ? text(hint).style(HINT)
                : richText(Text.from(Line.from(List.of(mode, Span.styled(hint, HINT)))));
    }
    case THINKING -> richText(statusBar.shimmer("● 思考中…",
            qs + ijs + ns + cacheHit + " · Esc 取消 · Ctrl+C 退出", THINK, animTick, mode));
    case RUNNING_TOOL -> {
        String suffix = qs + ijs + ns + cacheHit + " · Esc 取消";
        String s = fitToolSummary(state.activeToolSummary(), state.activeTool(), suffix);
        yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
                suffix, RUNNING, animTick, mode));
    }
};
```

Do not add this suffix to the earlier compaction, menu, or draining branches.

- [ ] **Step 5: Run focused status tests**

Run:

```bash
./mvnw -pl springai-code-tui -am \
  -Dtest=BusyCacheHitStatusTest,CacheHitStatusBarWidthTest,ContextUsageTest,StatusBarTest,CodeTuiViewBusyNoticeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests pass, including the existing idle-width and busy-notice regressions.

- [ ] **Step 6: Run the module test suite**

Run:

```bash
./mvnw -pl springai-code-tui -am test
```

Expected: Maven exits with `BUILD SUCCESS` and all reactor tests pass.

- [ ] **Step 7: Commit the implementation**

```bash
git add \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/BusyCacheHitStatusTest.java
git commit -m "feat: show cache hit rate while busy

Co-Authored-By: CodeTui <noreply@codetui.dev>"
```

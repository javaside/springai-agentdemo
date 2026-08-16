# Idle Status Information Priority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the idle status line hide secondary shortcut help as one group before hiding `Enter 发送`, while preserving the permission mode tag, model, and dynamic context information for as long as the terminal width permits.

**Architecture:** Keep `CodeTuiView.statusLine()` as the single status dispatcher. Replace the current per-shortcut drop loop with a small package-private pure candidate selector that receives the width available after the permission mode tag; the rendered `IDLE` branch computes that budget with Unicode display widths and keeps the existing styled spans.

**Tech Stack:** Java 17+, JUnit 5, Maven, TamboUI `CharWidth`, existing `ViewScreen` off-screen renderer.

## Global Constraints

- Only the `IDLE` status branch changes; `THINKING`, `RUNNING_TOOL`, compacting, picker, approval, and other dedicated status lines remain unchanged.
- Preserve the full-width order: `Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · <模型><动态后缀>`.
- Treat `/model 切换模型 · Esc 取消 · Ctrl+C 退出` as one secondary-help group: all three appear or all three disappear.
- Candidate priority is: full line, then `Enter 发送 · <模型><动态后缀>`, then `<模型><动态后缀>`.
- A non-default permission mode tag remains visible and its Unicode display width must be deducted from the idle hint budget.
- Never truncate the model or dynamic suffix in application code; if the core status alone exceeds the terminal, return it intact and let terminal clipping be the final fallback.
- Use `dev.tamboui.text.CharWidth` display width, not Java `String.length()`.
- Do not add dependencies, change colors, or change context/cache/background data calculation and refresh behavior.
- Follow TDD: add assertions, run them red for the expected behavior gap, then modify production code.

## File Structure

- Modify `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitStatusBarWidthTest.java`: own all responsive idle-status rendering regressions and the exact-width boundary assertion.
- Modify `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`: compute the mode-aware width budget and select one of the three semantic idle candidates.
- No new production class: the behavior is specific to `CodeTuiView` status composition and does not justify a wider abstraction.

---

### Task 1: Semantic Idle Status Fitting

**Files:**
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitStatusBarWidthTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:3039-3088`

**Interfaces:**
- Consumes: `CodeTuiView.modeTag(PermissionMode)` returning the optional leading `Span`; `CodeTuiView.displayWidth(String)` using TamboUI `CharWidth`; `ContextUsage.suffix()` and `backgroundStatusSuffix()` producing the existing dynamic suffix.
- Produces: package-private static `String idleHint(String modelLabel, String dynamicSuffix, int availableWidth)`; the `IDLE` branch passes `terminalWidth() - displayWidth(mode.content())` for non-default modes and `terminalWidth()` for default mode.

- [ ] **Step 1: Add rendering fixtures and failing responsive assertions**

Extend `CacheHitStatusBarWidthTest` imports and fixture stubs so tests can control the model label and permission mode while reusing the existing context statistics:

```java
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;

import static org.junit.jupiter.api.Assertions.assertFalse;

private static class CtxStub implements SubmitHandler {
    private final String model;
    private final PermissionMode mode;

    private CtxStub() {
        this("deepseek-v4-pro", PermissionMode.DEFAULT);
    }

    private CtxStub(String model, PermissionMode mode) {
        this.model = model;
        this.mode = mode;
    }

    @Override public reactor.core.Disposable submit(String text) { return null; }
    @Override public String currentModel() { return model; }
    @Override public PermissionMode permissionMode() { return mode; }
    @Override public ContextStats contextStats() {
        return new ContextStats(100, 40, 50, 8, 2, 155_184L, 100_000L, 200_000L, 20, 10, 0, 0L,
                120_832L, 155_184L, 78);
    }
}

private static String idleScreen(Path root, CtxStub stub) {
    ConversationState state = new ConversationState();
    CodeTuiView view = new CodeTuiView(state, stub, root);
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    return ViewScreen.of(view, 80);
}
```

Add these tests. The medium-width test must assert all three secondary-help members disappear together; the core-only test uses a longer model to consume the room left for `Enter`; the permission test catches the current failure to deduct the leading mode span:

```java
@Test
@DisplayName("宽度不足时次要帮助整组隐藏，Enter 与动态状态保留")
void secondaryHelpDisappearsAsOneGroup(@TempDir Path root) {
    String screen = idleScreen(root, new CtxStub());

    assertTrue(screen.contains("Enter 发送"), screen);
    assertFalse(screen.contains("/model 切换模型"), screen);
    assertFalse(screen.contains("Esc 取消"), screen);
    assertFalse(screen.contains("Ctrl+C 退出"), screen);
    assertTrue(screen.contains("deepseek-v4-pro"), screen);
    assertTrue(screen.contains("上下文 78%"), screen);
    assertTrue(screen.contains("缓存命中 78%"), screen);
}

@Test
@DisplayName("更窄时 Enter 也让位，只保留模型与动态状态")
void primaryActionDisappearsBeforeCoreStatus(@TempDir Path root) {
    String model = "provider/very-long-context-model-name";
    String screen = idleScreen(root, new CtxStub(model, PermissionMode.DEFAULT));

    assertFalse(screen.contains("Enter 发送"), screen);
    assertFalse(screen.contains("/model 切换模型"), screen);
    assertTrue(screen.contains(model), screen);
    assertTrue(screen.contains("上下文 78%"), screen);
    assertTrue(screen.contains("缓存命中 78%"), screen);
}

@Test
@DisplayName("权限模式标签占用宽度后，次要帮助组主动让位")
void permissionModeTagParticipatesInWidthBudget(@TempDir Path root) {
    String screen = idleScreen(root, new CtxStub("deepseek-v4-pro", PermissionMode.ACCEPT_EDITS));

    assertTrue(screen.contains("自动接受编辑"), screen);
    assertFalse(screen.contains("/model 切换模型"), screen);
    assertFalse(screen.contains("Esc 取消"), screen);
    assertFalse(screen.contains("Ctrl+C 退出"), screen);
    assertTrue(screen.contains("deepseek-v4-pro"), screen);
    assertTrue(screen.contains("上下文 78%"), screen);
    assertTrue(screen.contains("缓存命中 78%"), screen);
}

@Test
@DisplayName("候选恰好等于可用宽度时不提前降级")
void exactWidthKeepsCandidate() {
    String full = "Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · model · 上下文 1%";
    int exactWidth = dev.tamboui.text.CharWidth.of(full);

    assertEquals(full, CodeTuiView.idleHint("model", " · 上下文 1%", exactWidth));
}
```

Retain the existing cache-hit regression, but make its `120`-column contrast assertion compatible with the fact that the view's internal fallback width is 80: use the new exact-width test as proof that the full candidate remains available when the budget is wide enough, and remove the misleading `ViewScreen.of(v, 120)`/`Enter 发送` assertion if it assumes the render buffer width changes `terminalWidth()`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -q -pl springai-code-tui -am \
  -Dtest=CacheHitStatusBarWidthTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL for the new contract. At minimum, `secondaryHelpDisappearsAsOneGroup` fails because the current loop may preserve part of the secondary group, and compilation fails until the planned three-argument package-private `idleHint(...)` interface exists. Add the interface signature only if needed to move from compile error to behavioral red; its temporary body may delegate to the old implementation, but do not implement the new candidate policy before observing the behavioral failures.

- [ ] **Step 3: Implement the minimal semantic candidate selector**

In `CodeTuiView.statusLine()`, derive the mode-aware budget once and pass it to the idle fitter:

```java
case IDLE -> {
    int modeWidth = mode == null ? 0 : displayWidth(mode.content());
    String hint = idleHint(statusModelLabel(),
            ctxUsage.suffix() + backgroundStatusSuffix(),
            terminalWidth() - modeWidth);
    yield mode == null ? text(hint).style(HINT)
            : richText(Text.from(Line.from(List.of(mode, Span.styled(hint, HINT)))));
}
```

Replace the old per-item loop with the semantic candidates below. Keep it package-private static so the exact-width boundary can be tested directly without introducing a production-only test hook:

```java
static String idleHint(String modelLabel, String dynamicSuffix, int availableWidth) {
    String core = modelLabel + dynamicSuffix;
    String primaryAndCore = "Enter 发送 · " + core;
    String full = "Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + core;

    if (displayWidth(full) <= availableWidth) return full;
    if (displayWidth(primaryAndCore) <= availableWidth) return primaryAndCore;
    return core;
}
```

Update the method Javadoc to state the three groups and the all-or-nothing secondary-help behavior. Remove the obsolete per-shortcut drop order documentation and the `StringBuilder` loop.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
mvn -q -pl springai-code-tui -am \
  -Dtest=CacheHitStatusBarWidthTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS with no compilation errors or test failures.

- [ ] **Step 5: Run related UI regression tests**

Run:

```bash
mvn -q -pl springai-code-tui -am \
  -Dtest=CacheHitStatusBarWidthTest,CodeTuiViewPermissionModeTest,BusyCacheHitStatusTest,CodeTuiViewInterjectionStatusTest,StatusBarTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS. This confirms the idle change does not regress mode tags, busy cache status, interjection suffix fitting, or status animation rendering.

- [ ] **Step 6: Run the full module test suite and build checks**

Run:

```bash
mvn -q -pl springai-code-tui -am test
mvn -q -pl springai-code-tui -am package -DskipTests
git diff --check
```

Expected: all Maven commands exit 0; `git diff --check` prints no output.

- [ ] **Step 7: Review the final diff and commit the implementation**

Review only the intended files:

```bash
git diff -- springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitStatusBarWidthTest.java
git status --short
```

Expected: production changes are limited to mode-aware idle width budgeting and the three candidates; tests cover full-group disappearance, primary-action disappearance, permission-tag budgeting, dynamic suffix preservation, and exact-width inclusion.

Commit:

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitStatusBarWidthTest.java
git commit -m "$(cat <<'EOF'
fix: prioritize idle status context in narrow terminals

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
```

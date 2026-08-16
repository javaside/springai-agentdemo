# Cache Hit Percentage Highlight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Highlight only the `N%` value in status-bar cache hit text with bold mint styling in idle, thinking, and tool-running states.

**Architecture:** Add one package-private `StatusBar.cacheHitSpans(String, Style)` formatter that preserves the caller's base style while splitting every `缓存命中 N%` occurrence into ordinary text and a highlighted percentage Span. Use it for the idle hint and for animated-state suffixes; retain the existing plain strings for width calculation and tool-summary fitting.

**Tech Stack:** Java 21, JUnit 5, Maven, tamboui `Text`/`Line`/`Span` and off-screen `Buffer` rendering

## Global Constraints

- Highlight only the complete percentage value, including `%`; keep `缓存命中` and separators in their existing state-specific style.
- Use `Color.indexed(115)` with bold for the percentage.
- Apply the highlight only to `IDLE`, `THINKING`, and `RUNNING_TOOL` status lines.
- Keep `/context`, compaction, selectors, approval prompts, and draining-subagent status lines unchanged.
- Keep cache-hit calculation, collection, refresh timing, wording, ordering, width priorities, and tool-summary fitting unchanged.
- Do not introduce ANSI sequences into strings or width calculations.
- Do not color percentages according to their numeric value and do not highlight other status-bar numbers.

---

### Task 1: Render the Cache Hit Percentage as a Highlighted Span

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java:46-60`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/StatusBar.java:25-60`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:3039-3058`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitPercentHighlightTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/StatusBarTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitStatusBarWidthTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/BusyCacheHitStatusTest.java`

**Interfaces:**
- Consumes: plain status text containing zero or more `缓存命中 N%` segments and the state-specific base `Style`.
- Produces: `Theme.CACHE_HIT_VALUE`, a bold mint `Style` using `Color.indexed(115)`.
- Produces: package-private static `List<Span> StatusBar.cacheHitSpans(String text, Style base)`, preserving all text and applying `CACHE_HIT_VALUE` only to each matched `N%`.

- [ ] **Step 1: Add failing pure formatter tests**

Append these tests and imports to `StatusBarTest`:

```java
import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Test
void cacheHitSpansHighlightsOnlyPercentage() {
    Style base = Theme.HINT;
    List<Span> spans = StatusBar.cacheHitSpans(
            "deepseek-chat · 上下文 50% · 缓存命中 78%", base);

    assertEquals("deepseek-chat · 上下文 50% · 缓存命中 78%",
            spans.stream().map(Span::content).collect(java.util.stream.Collectors.joining()));
    Span value = spans.stream().filter(s -> s.content().equals("78%")).findFirst().orElseThrow();
    assertEquals(Color.indexed(115), value.style().fg().orElseThrow());
    assertTrue(value.style().effectiveModifiers().contains(Modifier.BOLD));
    assertTrue(spans.stream()
            .filter(s -> !s.content().equals("78%"))
            .allMatch(s -> s.style().equals(base)));
}

@Test
void cacheHitSpansLeavesOtherPercentagesAndMissingCacheTextAlone() {
    Style base = Theme.DIM;
    List<Span> plain = StatusBar.cacheHitSpans(" · 上下文 50% · Esc 取消", base);

    assertEquals(1, plain.size());
    assertEquals(" · 上下文 50% · Esc 取消", plain.getFirst().content());
    assertEquals(base, plain.getFirst().style());
    assertFalse(plain.getFirst().style().effectiveModifiers().contains(Modifier.BOLD));
}
```

If imports already exist, merge them without duplication. The first test explicitly proves that the context percentage remains at the base style.

- [ ] **Step 2: Run the formatter tests and verify red state**

Run:

```bash
./mvnw -pl springai-code-tui -am \
  -Dtest=StatusBarTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `StatusBar.cacheHitSpans(String, Style)` does not exist.

- [ ] **Step 3: Add failing off-screen tests for all three status states**

Create `CacheHitPercentHighlightTest`. Reuse the `SubmitHandler` stub shape and the 15-argument `ContextStats` fixture from `BusyCacheHitStatusTest`, with cache fields `120_832L`, `155_184L`, and `78`. Add these three test flows:

```java
@Test
void idleHighlightsOnlyCacheHitValue(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();

    assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.HINT);
}

@Test
void thinkingHighlightsOnlyCacheHitValue(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    state.onTurnStarted(1L);

    assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.DIM);
}

@Test
void runningToolHighlightsOnlyCacheHitValue(@TempDir Path root) {
    ConversationState state = new ConversationState();
    CodeTuiView view = view(state, root, statsWithCacheHit());
    state.onUserMessage(1L, "hello");
    view.ctxUsageForTest().refresh();
    state.onTurnStarted(1L);
    state.onToolStarted(1L, "Read", "{\"filePath\":\"/tmp/a\"}");

    assertCacheHitStyle(ViewScreen.bufferOf(view, 80), Theme.DIM);
}
```

Implement the test helper by scanning each `Buffer` row into visible symbols, finding the row containing `缓存命中 78%`, converting the substring start to terminal columns with the same cell walk, and asserting:

```java
assertEquals(expectedLabelStyle, firstCellOfSubstring(buffer, "缓存命中").style());
Cell value = firstCellOfSubstring(buffer, "78%");
assertEquals(Color.indexed(115), value.style().fg().orElseThrow());
assertTrue(value.style().effectiveModifiers().contains(Modifier.BOLD));
```

`firstCellOfSubstring(Buffer, String)` must skip continuation cells when assembling row text, then walk cells again to return the first non-continuation cell corresponding to the substring. Fail with an assertion message containing the missing substring if no row contains it. This tests the rendered UI rather than only the formatter.

- [ ] **Step 4: Run the new off-screen tests and verify red state**

Run:

```bash
./mvnw -pl springai-code-tui -am \
  -Dtest=CacheHitPercentHighlightTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the three tests fail because `78%` currently inherits `HINT` in `IDLE` and `DIM` in busy states.

- [ ] **Step 5: Add the centralized cache-hit value style**

Add this constant near the other status styles in `Theme`:

```java
/** 状态栏缓存命中率数值：冷薄荷加粗，强调数据但不表达成功或警告。 */
static final Style CACHE_HIT_VALUE = Style.create().fg(Color.indexed(115)).bold();
```

Do not reuse `WELCOME_ACCENT` or `MODE_PLAN`: the color is intentionally shared, but this status datum needs its own semantic style name.

- [ ] **Step 6: Implement the shared Span formatter**

Add imports and the formatter to `StatusBar`:

```java
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.javaside.springai.codetui.ui.Theme.CACHE_HIT_VALUE;

private static final Pattern CACHE_HIT = Pattern.compile("(缓存命中 )(\\d+%)");

static List<Span> cacheHitSpans(String text, Style base) {
    Matcher matcher = CACHE_HIT.matcher(text);
    List<Span> spans = new ArrayList<>();
    int cursor = 0;
    while (matcher.find()) {
        if (matcher.start() > cursor) {
            spans.add(Span.styled(text.substring(cursor, matcher.start()), base));
        }
        spans.add(Span.styled(matcher.group(1), base));
        spans.add(Span.styled(matcher.group(2), CACHE_HIT_VALUE));
        cursor = matcher.end();
    }
    if (cursor < text.length()) {
        spans.add(Span.styled(text.substring(cursor), base));
    }
    if (spans.isEmpty()) {
        spans.add(Span.styled(text, base));
    }
    return spans;
}
```

This deliberately accepts multiple matches, preserves every character, and leaves unrelated percentages untouched.

- [ ] **Step 7: Use the formatter in animated suffixes**

In `StatusBar.shimmer(...)`, replace the one-Span suffix append:

```java
if (!suffix.isEmpty()) spans.add(Span.styled(suffix, DIM));
```

with:

```java
if (!suffix.isEmpty()) spans.addAll(cacheHitSpans(suffix, DIM));
```

The shimmer label, optional leading permission-mode Span, and suffix order remain unchanged.

- [ ] **Step 8: Use the formatter in the idle status branch**

In `CodeTuiView.statusLine()`, keep `hint` as the same plain string returned by `idleHint(...)`; it remains the input to existing width selection. Replace only the final `IDLE` rendering with:

```java
yield mode == null
        ? richText(Text.from(Line.from(StatusBar.cacheHitSpans(hint, HINT))))
        : richText(Text.from(Line.from(withLeading(mode, StatusBar.cacheHitSpans(hint, HINT)))));
```

Add this package-private helper near `modeTag(...)` so the list passed by the formatter is not mutated and the permission tag remains first:

```java
static List<Span> withLeading(Span leading, List<Span> rest) {
    List<Span> spans = new ArrayList<>(rest.size() + 1);
    spans.add(leading);
    spans.addAll(rest);
    return spans;
}
```

Do not alter `idleHint(...)`, `ctxUsage.suffix()`, `cacheHitSuffix()`, `fitToolSummary(...)`, or any earlier dedicated status-line branch.

- [ ] **Step 9: Run focused tests and verify green state**

Run:

```bash
./mvnw -pl springai-code-tui -am \
  -Dtest=CacheHitPercentHighlightTest,StatusBarTest,BusyCacheHitStatusTest,CacheHitStatusBarWidthTest,CodeTuiViewModeIndicatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: Maven reports `BUILD SUCCESS`; pure Span assertions pass, all three rendered states use the requested style, and existing 80-column and permission-mode behavior remains green.

- [ ] **Step 10: Run the module test suite**

Run:

```bash
./mvnw -pl springai-code-tui -am test
```

Expected: Maven exits with `BUILD SUCCESS` and all reactor tests pass.

- [ ] **Step 11: Commit the implementation**

```bash
git add \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/StatusBar.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/StatusBarTest.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CacheHitPercentHighlightTest.java
git commit -m "$(cat <<'EOF'
feat: highlight cache hit percentage

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
```

# Code TUI Event-Driven UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Code TUI's permanent render/drain polling with coalesced state-change notifications while preserving every existing command, panel, queue, modal, terminal and Agent behavior.

**Architecture:** `ConversationState` and the existing Agent components remain durable truth sources. They publish lock-free dirty signals to a `UiUpdateCoordinator`, which coalesces concurrent changes and schedules bounded work on the single TamboUI thread. Permanent ticks disappear; streaming preview, resize settle, animation, context refresh and output continuation use demand-driven one-shot scheduling.

**Tech Stack:** Java 17, Maven, Reactor, TamboUI 0.4.0 with the in-repository shadow patch, JUnit 5, Python PTY/pyte smoke tests.

**Spec:** `docs/superpowers/specs/2026-08-30-code-tui-event-driven-ui-design.md`

## Global Constraints

- Delete the permanent `tickRate(100ms)` redraw and `scheduleRepeating(..., 66ms)` drain loop.
- Keep `ConversationState` and the current component queues as durable truth; notifications carry dirty categories only.
- Never render, print to the terminal, manipulate input state, auto-dispatch, or respond to UI controls from Agent/Reactor/tool threads.
- Publish notifications only after releasing source locks; listener failures must not escape into producers.
- Keep all existing commands, panels, output formatting, modal liveness, cancellation, queue/interjection/background priority, resize replay, IME repair and attention behavior.
- Coalesce high-frequency tokens; never enqueue one UI task per token.
- Enforce a strict physical-row output budget and return to the event loop between batches.
- Use demand-driven one-shot tasks for preview, output continuation, resize settle, animation and context usage; idle UI must produce no periodic ANSI output.
- Do not delete existing tests or weaken their assertions to make the refactor pass.
- Run Maven commands with `-am`; targeted `-Dtest` commands must include `-Dsurefire.failIfNoSpecifiedTests=false`.
- Commit each task independently after its targeted tests pass.

## File Structure

New focused units:

- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiDirty.java` — dirty-bit constants.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiChangeListener.java` — lock-free change callback.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiChangeSource.java` — source binding contract.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinator.java` — coalescing, lifecycle and one-shot scheduling.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshController.java` — debounced single-flight usage refresh.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java` — bounded, resumable physical output.
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/OutputCursor.java` — incremental output cursor.
- `springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py` — output/input concurrency and idle-zero-output PTY test.

Existing ownership remains:

- `ConversationState` owns durable UI-facing state and Agent event filtering.
- `CodeTuiView` owns UI-local state and the control-flow processing order.
- `InlineTuiRunner` owns event-loop wakeup and redraw execution.
- `InlineDisplay` owns terminal diffing, print batches and IME repair.

---

### Task 1: Add an active redraw primitive to InlineTuiRunner

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineTuiRunner.java`
- Modify if required by renderer access: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineViewport.java`
- Test: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/tui/InlineTuiRunnerEventDrivenTest.java`

**Interfaces:**
- Consumes: existing `BlockingQueue<Event>`, render-thread marker and `Renderer` callback.
- Produces:
  ```java
  public void requestUiUpdate(Runnable action)
  public void requestRender()
  ```
  Both methods are thread-safe, coalesced, become no-ops after close, and cause at most one draw for the actions collected by one wake event.

- [ ] **Step 1: Write failing event-driven runner tests**

Create `InlineTuiRunnerEventDrivenTest` using the module's existing fake backend pattern. Cover:

```java
@Test
void requestUiUpdateWakesRunnerWhenTicksAreDisabled() { /* latch action; assert one draw */ }

@Test
void concurrentRenderRequestsAreCoalesced() { /* 1_000 callers; frame growth remains bounded */ }

@Test
void actionFailureDoesNotKillFollowingActions() { /* first throws, second counts down */ }

@Test
void idleRunnerDoesNotRenderWithoutEvents() { /* wait >= 3 poll timeouts; frame count unchanged */ }

@Test
void quitWakesBlockedEventLoop() { /* quit; runner thread joins promptly */ }
```

Use latches/barriers rather than sleeps for action completion. A short bounded wait is allowed only as a failure deadline.

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```bash
mvn -pl springai-tamboui-inline-patch \
  -Dtest=InlineTuiRunnerEventDrivenTest test
```

Expected: test compilation fails because `requestUiUpdate` and `requestRender` do not exist.

- [ ] **Step 3: Implement coalesced active updates**

Add runner-owned state:

```java
private final ConcurrentLinkedQueue<Runnable> uiActions = new ConcurrentLinkedQueue<>();
private final AtomicBoolean uiUpdateQueued = new AtomicBoolean();
private final AtomicBoolean renderRequested = new AtomicBoolean();
private volatile Renderer activeRenderer;
```

Add a private wake event/action that drains only the actions present at the start of that event, catches each `Throwable` through `handleThrowable`, consumes `renderRequested`, and calls `viewport.draw(activeRenderer::render)` once. If work arrives while the batch runs, clear/reacquire `uiUpdateQueued` and enqueue another wake rather than looping without bound.

Implement:

```java
public void requestUiUpdate(Runnable action) {
    if (action == null || !running.get()) return;
    uiActions.offer(action);
    renderRequested.set(true);
    enqueueUiWake();
}

public void requestRender() {
    if (!running.get()) return;
    renderRequested.set(true);
    enqueueUiWake();
}
```

Set `activeRenderer` before the initial draw and clear it in `finally`. Make `quit()` enqueue a wake after setting `running=false` so a blocking event loop exits promptly. Preserve `runOnRenderThread` and `runLater` compatibility.

- [ ] **Step 4: Run patch tests**

```bash
mvn -pl springai-tamboui-inline-patch \
  -Dtest=InlineTuiRunnerEventDrivenTest test
mvn -pl springai-tamboui-inline-patch test
```

Expected: all tests pass; idle test records no additional frames.

- [ ] **Step 5: Commit**

```bash
git add springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineTuiRunner.java \
        springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineViewport.java \
        springai-tamboui-inline-patch/src/test/java/dev/tamboui/tui/InlineTuiRunnerEventDrivenTest.java
git commit -m "feat(tamboui): add coalesced active UI redraw"
```

Omit `InlineViewport.java` from `git add` if it was not modified.

---

### Task 2: Add dirty-change contracts and lock-free ConversationState notifications

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiDirty.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiChangeListener.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiChangeSource.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateNotificationTest.java`

**Interfaces:**
- Produces:
  ```java
  public final class UiDirty {
      public static final int NONE = 0;
      public static final int OUTPUT = 1;
      public static final int VIEW = 1 << 1;
      public static final int CONTROL = 1 << 2;
      public static final int ALL = OUTPUT | VIEW | CONTROL;
      public static boolean contains(int bits, int flag);
  }

  @FunctionalInterface
  public interface UiChangeListener {
      void onUiChanged(int dirtyBits);
      static UiChangeListener noop();
  }

  public interface UiChangeSource {
      void setUiChangeListener(UiChangeListener listener);
      long uiVersion();
  }
  ```
- `ConversationState` additionally produces:
  ```java
  public synchronized boolean hasPendingOutput();
  public synchronized boolean hasCompleteStreamingLine();
  ```

- [ ] **Step 1: Write the dirty and notification tests**

Create parameterized cases mapping existing mutations to expected bits. Include at least:

```java
pushInfo                         -> OUTPUT | VIEW
onAssistantToken(valid)         -> OUTPUT | VIEW
onTurnStarted/Complete/Error     -> OUTPUT | VIEW | CONTROL
onToolStarted/Finished           -> OUTPUT | VIEW | CONTROL
onTodoUpdated(valid controller)  -> VIEW
queue add/poll/clear             -> VIEW | CONTROL
modal add/remove/clear           -> VIEW | CONTROL
background state change          -> OUTPUT | VIEW | CONTROL
notice real value change         -> VIEW
filtered late event              -> NONE
unknown task/no-op mutation       -> NONE
```

Add a lock-safety test where the listener starts a second thread that calls a synchronized snapshot and waits for it before returning. It must complete, proving notification occurs outside the state monitor. Add a throwing-listener test and assert state/version still advance and later mutations still work.

- [ ] **Step 2: Run tests and verify failure**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=ConversationStateNotificationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the update contracts do not exist.

- [ ] **Step 3: Implement update contracts**

Implement `UiDirty` as constants plus:

```java
public static boolean contains(int bits, int flag) {
    return (bits & flag) == flag;
}
```

Normalize null listener to `noop()` in all sources.

- [ ] **Step 4: Refactor ConversationState mutations to publish outside locks**

Replace method-level `synchronized` where publication is needed with this pattern:

```java
public void pushInfo(String text) {
    Change change;
    synchronized (this) {
        pending.add(new OutputLine(text, OutputLine.Kind.INFO));
        change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
    }
    publish(change);
}
```

Use a private immutable `Change(long version, int bits)` and private helpers. `publish` reads the current volatile listener, catches `RuntimeException`, logs and returns. Do not publish for no-op or filtered mutations. Nested mutation helpers must return/merge bits rather than publish twice. Preserve existing modal cancellation guarantees and perform external responder/cancel calls outside the state lock where safe; tests must prove every removed modal is awakened exactly once.

- [ ] **Step 5: Run state regressions**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=ConversationStateNotificationTest,ConversationStateQueueTest,ConversationStateResetTest,ConversationStateBackgroundTest,ConversationStateModalQueueTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl springai-code-tui -am \
  -Dtest='ConversationState*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all state tests pass with existing modal, queue and late-event semantics unchanged.

- [ ] **Step 6: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ConversationStateNotificationTest.java
git commit -m "feat(code-tui): publish durable UI state changes"
```

---

### Task 3: Implement UiUpdateCoordinator coalescing and one-shot scheduling

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinator.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiDirtyTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinatorTest.java`

**Interfaces:**
- Consumes: `InlineTuiRunner.requestUiUpdate`, `UiDirty`.
- Produces:
  ```java
  public final class UiUpdateCoordinator implements UiChangeListener, AutoCloseable {
      public enum Lifecycle { NEW, RUNNING, STOPPING, STOPPED }
      @FunctionalInterface public interface UpdateProcessor {
          UpdateResult processUpdates(int dirtyBits);
      }
      public record UpdateResult(boolean outputRemaining,
                                 boolean previewPending,
                                 boolean animationActive,
                                 boolean contextUsageDirty) {
          public static UpdateResult idle();
      }

      public UiUpdateCoordinator(InlineTuiRunner runner,
                                 ScheduledExecutorService scheduler,
                                 UpdateProcessor processor);
      public void start();
      @Override public void onUiChanged(int dirtyBits);
      public void scheduleOutputContinuation(Duration delay);
      public void schedulePreview(Duration delay);
      public void scheduleResizeSettle(Duration delay, Runnable uiAction);
      public void updateAnimationDemand(boolean active, Duration frameDelay);
      public Lifecycle lifecycle();
      public int pendingDirtyBits();
      public boolean updateScheduled();
      public void stop();
      @Override public void close();
  }
  ```

- [ ] **Step 1: Write deterministic coordinator tests**

Use a fake/single-thread scheduler or controlled executor and a runner seam. Test:

- 1,000 concurrent `onUiChanged(OUTPUT)` calls produce bounded scheduled UI work;
- `OUTPUT | VIEW | CONTROL` bits are all delivered;
- a publish between clearing `scheduled` and the final recheck produces a second batch;
- `outputRemaining=true` schedules continuation even without a producer event;
- preview, resize and animation each have at most one current generation;
- replacing resize settle prevents the stale action from running;
- `stop()` cancels timers and late callbacks are no-ops;
- processor failure does not permanently hold `scheduled=true`.

- [ ] **Step 2: Run and verify failure**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=UiDirtyTest,UiUpdateCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure because `UiUpdateCoordinator` is absent.

- [ ] **Step 3: Implement coordinator atomics**

Use:

```java
private final AtomicInteger dirtyBits = new AtomicInteger();
private final AtomicBoolean scheduled = new AtomicBoolean();
private final AtomicLong generation = new AtomicLong();
private final AtomicReference<Lifecycle> lifecycle =
        new AtomicReference<>(Lifecycle.NEW);
```

`onUiChanged` ORs bits and increments coordinator-local generation. This avoids comparing unrelated source-local versions. Only the CAS winner calls `runner.requestUiUpdate(this::runBatch)`. In `runBatch`, atomically take dirty bits, call processor once, schedule demand-driven follow-ups, clear `scheduled` in `finally`, then recheck bits/generation and reacquire scheduling if needed. Never drain repeatedly within one UI action.

Implement one `AtomicReference<ScheduledFuture<?>>` per timer category. A scheduled callback publishes dirty bits or requests a UI action; it never touches `CodeTuiView` state directly off-thread.

- [ ] **Step 4: Run coordinator tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=UiDirtyTest,UiUpdateCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all tests pass repeatedly.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinator.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiDirtyTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/UiUpdateCoordinatorTest.java
git commit -m "feat(code-tui): add coalesced UI update coordinator"
```

---

### Task 4: Publish changes from Agent-owned external state

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/interjection/Interjections.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/subagent/SubagentRunner.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/background/BackgroundTaskRegistry.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/mcp/McpRegistry.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/SubmitHandler.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`
- Test: corresponding notification tests under each package.

**Interfaces:**
- Consumes: `UiChangeSource`, `UiChangeListener`, `UiDirty`.
- Produces via `SubmitHandler`:
  ```java
  default void setUiChangeListener(UiChangeListener listener) { }
  ```
  `CodingAgent` fans this listener out to Interjections, SubagentRunner, BackgroundTaskRegistry and McpRegistry. `ConversationState` is bound separately by the View.

- [ ] **Step 1: Write source-specific failing tests**

Create:

- `InterjectionsNotificationTest`: offer/deliver/refill/history removal notify outside lock; empty operations do not.
- `SubagentRunnerNotificationTest`: foreground in-flight increment/decrement emits `VIEW | CONTROL`; every success/error/interruption path decrements; background-only count emits `VIEW`.
- `BackgroundTaskRegistryNotificationTest`: register, real complete, kill and consume notify; duplicate/no-op operations do not; completion includes `CONTROL`.
- `McpRegistryNotificationTest`: connecting and result changes emit `VIEW` outside internal locks.

- [ ] **Step 2: Run and verify failure**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=InterjectionsNotificationTest,SubagentRunnerNotificationTest,BackgroundTaskRegistryNotificationTest,McpRegistryNotificationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation failure because sources do not implement the listener contract.

- [ ] **Step 3: Implement lock-free publication in each source**

Use the same pattern as ConversationState: mutate under the source's existing lock/atomics, capture bits, publish afterward with exception isolation. Preserve `Interjections.fireDelivered()` outside-lock behavior and do not merge its business callback with UI wakeup. Publish background completion as `VIEW | CONTROL` so automatic delivery wakes without drain polling.

Implement `CodingAgent.setUiChangeListener` fan-out. Keep the `SubmitHandler` default no-op so existing test stubs compile unchanged.

- [ ] **Step 4: Run focused and existing component tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=InterjectionsNotificationTest,InterjectionsTest,SubagentRunnerNotificationTest,SubagentRunnerParallelTest,SubagentRunnerBackgroundTest,BackgroundTaskRegistryNotificationTest,BackgroundTaskRegistryTest,McpRegistryNotificationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all pass; no existing cancellation or background lifecycle assertion changes.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent
git commit -m "feat(code-tui): publish agent-side UI changes"
```

---

### Task 5: Replace soft drain limiting with strict resumable physical output

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/OutputCursor.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output/PhysicalOutputQueue.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScrollbackPrinter.java`
- Modify as needed for incremental diff: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/DiffRenderer.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/DrainBurstCapTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/StrictOutputFairnessTest.java`

**Interfaces:**
- Produces:
  ```java
  interface OutputCursor {
      boolean hasNext();
      PhysicalLine next();
  }

  public final class PhysicalOutputQueue {
      public record PhysicalLine(String plain, Text styled) {
          public static PhysicalLine plain(String value);
          public static PhysicalLine styled(Text value);
      }
      public record BatchResult(int rowsWritten,
                                boolean remaining,
                                boolean timeBudgetExhausted) { }

      public void enqueue(ConversationState.OutputLine line);
      public void enqueueStreamingLines(List<String> lines);
      public BatchResult drain(int maxPhysicalRows, long maxNanos);
      public boolean isEmpty();
      public void clear();
  }
  ```
- `ScrollbackPrinter` produces cursor factories that preserve its current rendering state and styles rather than writing an unbounded item directly to the terminal sink.

- [ ] **Step 1: Strengthen existing burst tests before implementation**

Remove `SLACK=200`. Change all single-batch assertions to `<= 300`. Add cases for:

- one very long assistant output;
- one large edit/write diff;
- 5,000 streaming lines;
- a no-newline line wider than thousands of terminal columns;
- final full output equality and ordering after repeated batches.

Add `StrictOutputFairnessTest` with a fake event queue: drain one batch, process a key action, then continue output; assert the key action occurs before final output completion.

- [ ] **Step 2: Run and verify the strict tests fail**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=DrainBurstCapTest,StrictOutputFairnessTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: current atomic `OutputLine` path exceeds the strict cap or new interfaces are absent.

- [ ] **Step 3: Implement bounded cursors**

Convert logical output into lazy physical-line cursors. Preserve Markdown code-fence/highlight state across cursor calls. Diff cursor must preserve header/hunk/line styles and real line numbers. Do not materialize the complete physical output list for one huge item. `drain` stops before fetching line `maxPhysicalRows + 1` and checks `System.nanoTime()` against `maxNanos` between physical lines.

Keep `InlineRenderBatch` around each batch, not each line. Store the active cursor at the head of `PhysicalOutputQueue`; remove it only when exhausted.

- [ ] **Step 4: Run output and formatting regressions**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=DrainBurstCapTest,StrictOutputFairnessTest,ScrollbackPrinterTest,DiffRendererTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

If either named formatting test is absent, obtain the exact existing class names with Glob and rerun using those names. Expected: strict cap, ordering, Markdown and diff tests pass.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/output \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScrollbackPrinter.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/DiffRenderer.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/DrainBurstCapTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/StrictOutputFairnessTest.java
git commit -m "refactor(code-tui): strictly batch physical terminal output"
```

---

### Task 6: Make context usage refresh demand-driven and single-flight

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshController.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ContextUsageTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshControllerTest.java`

**Interfaces:**
- `ContextUsage.refresh()` changes from `void` to:
  ```java
  boolean refresh(); // true only when cached visible data changed
  ```
- Produces:
  ```java
  final class ContextUsageRefreshController implements AutoCloseable {
      ContextUsageRefreshController(ContextUsage usage,
                                    Executor executor,
                                    ScheduledExecutorService scheduler,
                                    Duration debounce,
                                    Runnable onRefreshed);
      void markDirty();
      boolean refreshInFlight();
      void stop();
      @Override public void close();
  }
  ```

- [ ] **Step 1: Write failing refresh-controller tests**

Cover burst coalescing, one in-flight refresh, one catch-up refresh after becoming dirty during execution, no callback when cache is unchanged, callback on changed cache, exception retention of old cache, and no work after stop.

- [ ] **Step 2: Run and verify failure**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=ContextUsageTest,ContextUsageRefreshControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: controller absent and `refresh()` return assertions fail to compile.

- [ ] **Step 3: Implement refresh result and controller**

In `ContextUsage.refresh`, compute the next immutable cache value, compare it with the old value, assign the volatile cache, and return whether visible data changed. On exception, retain old cache and return false.

The controller debounces `markDirty`, runs refresh on the existing dedicated executor, and invokes `onRefreshed` only after a changed result. During an in-flight refresh, another dirty mark sets a catch-up flag; completion schedules at most one additional refresh.

- [ ] **Step 4: Run tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=ContextUsageTest,ContextUsageRefreshControllerTest,CacheHitStatusBarWidthTest,BusyCacheHitStatusTest,CacheHitPercentHighlightTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all existing context/cache text remains identical.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ContextUsage.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshController.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ContextUsageTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/update/ContextUsageRefreshControllerTest.java
git commit -m "refactor(code-tui): refresh context usage on demand"
```

---

### Task 7: Switch CodeTuiView to event-driven processing

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Modify only if assembly requires it: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewEventWiringTest.java`
- Modify existing `CodeTuiView*Test` only to use the new batch test seam; preserve assertions.

**Interfaces:**
- Consumes: all prior task interfaces.
- Produces test seams:
  ```java
  UiUpdateCoordinator coordinatorForTest();
  UiUpdateCoordinator.UpdateResult processUpdatesForTest(int dirtyBits);
  ```
- Keeps `tickForTest()` as a compatibility alias that runs one `UiDirty.ALL` batch without starting periodic work.

- [ ] **Step 1: Write event wiring and no-periodic-work tests**

Create parameterized wiring tests covering pending, streaming, turn transitions, tools, todo, subtasks, backgrounds, all modals, queued, interjections, in-flight counts, notice, context usage, attention and MCP async changes. Add tests that inspect `configure()` and startup behavior:

```java
assertFalse(config.ticksEnabled());
assertNoRepeatingDrainScheduled();
assertInitialAllSyncDrainsPreStartPending();
assertIdleCoordinatorHasNoScheduledTimer();
```

Add control-order assertions: output before modal, modal before attention, then interjection, queued and background auto-delivery.

- [ ] **Step 2: Run and verify failure**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiViewEventWiringTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: old tick/drain configuration violates the tests.

- [ ] **Step 3: Replace startup polling with bindings and initial sync**

In the constructor or pre-run lifecycle, build coordinator and context controller. Bind:

```java
state.setUiChangeListener(coordinator);
onSubmit.setUiChangeListener(coordinator);
```

In `configure`, set `ticksEnabled(false)`; retain tickRate only if builder rejects null, since disabled ticks never schedule. Delete `scheduleRepeating(...drain..., 66ms)`.

In `onStart`, print welcome on the UI thread, start coordinator, and publish `UiDirty.ALL` once to consume state written before View startup.

- [ ] **Step 4: Replace drain with one bounded update batch**

Refactor `drainInsideBatch` into:

```java
private UiUpdateCoordinator.UpdateResult processUpdates(int dirtyBits)
```

Maintain exact order:

1. stage/consume pending and complete streaming lines;
2. drain one strict physical batch;
3. synchronize modal identity and enter new modal;
4. advance attention;
5. recompute busy/in-flight;
6. deliver leftover interjection, else queued, else background result;
7. return flags for output remaining, preview, animation and usage dirtiness.

Do not loop until empty. Publishing local UI changes—input, selector movement, modal option movement, notice changes—must call coordinator directly because those states are not in Agent sources.

- [ ] **Step 5: Implement stop ordering**

`onStop` must stop coordinator and context controller, unbind both change sources to no-op, shut down the context executor, and then invoke existing superclass cleanup. Preserve `/clear` versus exit background semantics and MCP closure ownership.

- [ ] **Step 6: Run all View and core routing tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,DrainBurstCapTest,StrictOutputFairnessTest,UiUpdateCoordinatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: every previous View behavior and new event wiring passes.

- [ ] **Step 7: Commit the atomic switchover**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui
git commit -m "refactor(code-tui): drive agent UI updates by events"
```

Omit `CodeTuiApplication.java` if unchanged. This commit must not leave any state source dependent on the removed drain loop.

---

### Task 8: Convert preview, resize, animation and IME follow-up frames to demand-driven tasks

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Remove if no longer used: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ResizeSettle.java`
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Modify as required: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineViewport.java`
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/tui/InlineTuiRunner.java`
- Test: existing InlineDisplay and cursor/resize tests plus `CodeTuiViewEventWiringTest`.

**Interfaces:**
- `InlineDisplay` produces:
  ```java
  public boolean needsFollowUpFrame();
  ```
- Runner internally schedules follow-up draw requests only while this reports true; this API must not expose renderer ownership to CodeTuiView.

- [ ] **Step 1: Write failing demand-timer tests**

Add tests for:

- first streaming tail visibility;
- multiple tokens inside 150ms producing one pending preview wake;
- immediate clear when tail becomes empty;
- no preview task after turn completion;
- resize immediately renders and only latest settle generation replays;
- `parkCursorAtTop` always resets after settle failure;
- animation continues only in thinking/tool/compacting/running-task states;
- static idle has no timer;
- IME cursor-band frames complete on demand then return to zero output.

- [ ] **Step 2: Run focused tests and verify failure**

```bash
mvn -pl springai-tamboui-inline-patch \
  -Dtest=InlineDisplayDiffTest,InlineDisplayBaselineTest,InlineTuiRunnerEventDrivenTest test
mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiViewEventWiringTest,CodeTuiViewCursorParkTest,CodeTuiViewThinkingSettingsTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: tick-dependent cases fail after the prior task's tick removal.

- [ ] **Step 3: Implement one-shot preview, resize and animation demand**

Move preview adoption out of unconditional `render()`: token change marks preview pending; coordinator schedules one `VIEW` at remaining throttle delay. Empty tail bypasses throttle.

Replace `ResizeSettle.onTick()` with coordinator generation scheduling. Resize handler updates width and parks cursor immediately; latest settle UI action calls existing `replayAfterResize()` and restores cursor in `finally`.

Animation timer publishes `VIEW` for the next frame only when `processUpdates` reports active animation/elapsed-time demand. Stop scheduling immediately when demand disappears.

- [ ] **Step 4: Preserve InlineDisplay IME repair without global ticks**

Expose `needsFollowUpFrame` through the viewport/runner internally. After a draw that arms or continues the cursor repair band, schedule another one-shot render at the previous visual cadence. Stop when the counter reaches zero. Keep current diff, IL/DL, no-EL restore and CJK continuation behavior unchanged.

- [ ] **Step 5: Run patch and Code TUI tests**

```bash
mvn -pl springai-tamboui-inline-patch test
mvn -pl springai-code-tui -am \
  -Dtest='CodeTuiView*Test,ContextUsage*Test,DrainBurstCapTest,StrictOutputFairnessTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all pass; idle tests observe no continuing frames.

- [ ] **Step 6: Commit**

```bash
git add springai-tamboui-inline-patch/src/main/java \
        springai-tamboui-inline-patch/src/test/java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui
git commit -m "refactor(code-tui): schedule visual updates only on demand"
```

---

### Task 9: Add PTY concurrency and idle-output verification

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/render_diff_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/stream_box_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/resize_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/README.md`

**Interfaces:**
- Consumes: packaged Code TUI, local stub SSE provider and existing PTY helpers.
- Produces: one script that exits nonzero on lost/reordered input, output incompleteness, input starvation or idle ANSI writes.

- [ ] **Step 1: Write the new failing PTY smoke script**

The script must:

1. start a local SSE stub producing at least 5,000 numbered lines;
2. launch Code TUI in a PTY;
3. submit the output prompt;
4. while output continues, send ASCII characters, Backspace, Left and Right at fixed intervals;
5. record send time and first screen-observation time for each visible edit;
6. assert at least one edit appears before the final model line;
7. assert no edit is lost or reordered;
8. verify first line, final line and total model output count;
9. after full quiescence, clear the raw-byte accumulator, observe for at least 2 seconds and assert zero new terminal bytes;
10. print maximum observed input latency and fail above an explicitly documented threshold.

- [ ] **Step 2: Build and run the script against the pre-script implementation**

```bash
mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
mvn -q -pl springai-code-tui -am package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py
```

Expected before final tuning: the script exposes any starvation, residual periodic bytes or timing assumption.

- [ ] **Step 3: Update existing scripts to event-based waits**

- `render_diff_smoke.py`: remove tick-count assumptions; observe at least 2 seconds for idle zero output.
- `stream_box_smoke.py`: wait for on-demand IME follow-up completion, not a fixed number of 100ms ticks.
- `resize_smoke.py`: validate latest-generation settle by observable replay completion, not `33ms × 4` tick math.
- README: list every script, prerequisites and exact command; distinguish scripts requiring `npx`.

- [ ] **Step 4: Run all local/no-network PTY scripts**

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/event_driven_fairness_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/render_diff_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stream_box_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/resize_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/permission_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/interjection_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/attachment_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/clear_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/background_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/attention_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/edit_shortcut_smoke.py
```

Expected: all applicable scripts exit 0. Report missing Python modules or platform facilities explicitly; do not describe skipped scripts as passed.

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/test/resources/scripts
git commit -m "test(code-tui): stress event-driven output and input"
```

---

### Task 10: Complete regression, documentation alignment and Terminal.app validation

**Files:**
- Modify: `springai-code-tui/docs/implementation-map.md`
- Modify affected package documentation/comments that still describe 100ms ticks or 66ms drains.
- Do not modify release notes that describe historical released behavior unless clearly labeled as current architecture.

**Interfaces:**
- Consumes: completed event-driven implementation.
- Produces: current implementation documentation and final verification evidence.

- [ ] **Step 1: Update current architecture documentation**

Document the new chain:

```text
Agent/source mutation
  -> durable state/queue
  -> lock-free dirty notification
  -> UiUpdateCoordinator coalescing
  -> InlineTuiRunner active UI wake
  -> bounded processUpdates batch
  -> optional one-shot continuation/render
```

Replace current statements that `render()` runs every tick and `drain()` runs every 66ms. Preserve historical release-note facts.

- [ ] **Step 2: Search for stale polling assumptions**

Use Grep for:

```text
tickRate
66ms
100ms
every frame
每帧
scheduleRepeating
drain 每
animTick % 30
ResizeSettle
```

Classify every result as historical, test fixture, or stale current documentation. Remove only stale current assumptions.

- [ ] **Step 3: Run complete Maven verification**

```bash
mvn -pl springai-code-tui -am test
mvn -pl springai-code-tui -am clean package
```

Expected: BUILD SUCCESS for both. Inspect the packaged manifest/classpath and confirm `springai-tamboui-inline-patch` remains before official `tamboui-tui`.

- [ ] **Step 4: Re-run the PTY suite after clean package**

Run the commands from Task 9 again against the clean artifacts. Expected: same passing results, including idle zero-byte observation and bounded input latency.

- [ ] **Step 5: Perform real Terminal.app validation**

In macOS Terminal.app:

1. open two Code TUI windows;
2. start sustained large model/stub output in both;
3. type English continuously, including Backspace and cursor movement;
4. switch to a Chinese IME and repeatedly pre-edit, change candidates, cancel and commit text;
5. run for a recorded duration with recorded output scale;
6. note input latency, border/cursor displacement, tearing and application crashes;
7. if Terminal.app crashes, save the report and inspect GCD kevent, `setMarkedText:`, `selectedRange`, `NSTextInputContext` and `IMKInputSession` frames.

Report this result separately. If it is not run, state “Terminal.app manual validation not performed”; never claim the terminal crash is proven fixed.

- [ ] **Step 6: Commit documentation updates**

```bash
git add springai-code-tui/docs/implementation-map.md \
        springai-code-tui/src/main/java \
        springai-tamboui-inline-patch/src/main/java
git commit -m "docs(code-tui): document event-driven UI flow"
```

Only include source files whose comments changed; inspect `git diff --cached` before committing.

- [ ] **Step 7: Final verification record**

Run:

```bash
git status --short
git log --oneline -12
```

Expected: no unintended uncommitted files, and one focused commit per task. Summarize Maven results, every PTY script result, and Terminal.app manual status without overstating evidence.

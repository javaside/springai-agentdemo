# LLM Stream Idle Timeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Terminate an LLM stream with a readable error when no first chunk or next chunk arrives within the configured read timeout, so a hung OpenCode Go HTTP/2 request cannot leave code-tui running forever.

**Architecture:** Add a provider-neutral `ChatModel` decorator that applies Reactor's per-item `timeout(Duration)` operator only to `stream(Prompt)`. Add an `LlmProvider` decorator that caches this model wrapper, then compose it inside `UsageRecordingProvider` during application startup so all providers and all main/subagent/summary call paths share the protection while usage accounting remains outermost.

**Tech Stack:** Java 17, Spring AI 2.0 `ChatModel`, Project Reactor `Flux`, JUnit 5, Maven.

## Global Constraints

- Reuse `CODETUI_LLM_READ_TIMEOUT_SECONDS`; default 300 seconds, clamped to `[10, 3600]`.
- The timer starts at subscription and resets after every `ChatResponse`; it is not a total request deadline.
- Do not enable SDK/OkHttp `callTimeout` and do not change connect/read/write timeout wiring.
- Do not parse provider-specific quota payloads and do not retry timeout failures automatically.
- Preserve `call(Prompt)`, `getOptions()`, `getDefaultOptions()`, upstream errors, completion, and cancellation semantics.
- Timeout errors must identify the threshold and `CODETUI_LLM_READ_TIMEOUT_SECONDS`.
- Usage recording remains the outer decorator so usage observed before a timeout is committed on error.

## File Structure

- Create `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModel.java`: provider-neutral stream idle timeout and `ChatModel` method forwarding.
- Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModelTest.java`: stream timing, signal preservation, cancellation, and options forwarding tests.
- Create `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProvider.java`: `LlmProvider` forwarding decorator with one cached timeout-wrapped model.
- Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProviderTest.java`: provider delegation and wrapper caching tests.
- Modify `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`: compose timeout inside usage recording for every provider.
- Modify `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationThinkingConfigTest.java`: assert production registry wiring exposes timeout behavior without a network request.

---

### Task 1: Stream Idle Timeout ChatModel

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModel.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModelTest.java`

**Interfaces:**
- Consumes: `ChatModel`, `Prompt`, `Flux<ChatResponse>`, `Duration`, and `LlmTimeouts.READ_TIMEOUT_ENV`.
- Produces: package-visible `final class StreamIdleTimeoutChatModel implements ChatModel` with constructor `StreamIdleTimeoutChatModel(ChatModel delegate, Duration idleTimeout)`.

- [ ] **Step 1: Write failing tests for first-chunk and between-chunk timeout**

Create the test class with a controllable delegate and these core tests. Use short real durations to avoid introducing a `Scheduler` into production solely for tests:

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class StreamIdleTimeoutChatModelTest {
    private static final Prompt PROMPT = new Prompt("hi");
    private static final Duration TIMEOUT = Duration.ofMillis(100);

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatModel model(Flux<ChatResponse> stream) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return response("call"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return stream; }
        };
    }

    @Test
    void noFirstChunk_timesOutWithReadableMessage() {
        var wrapped = new StreamIdleTimeoutChatModel(model(Flux.never()), TIMEOUT);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertTrue(ex.getMessage().contains("等待模型流数据超时"));
        assertTrue(ex.getMessage().contains("0.1 秒"));
        assertTrue(ex.getMessage().contains(LlmTimeouts.READ_TIMEOUT_ENV));
    }

    @Test
    void stallsAfterFirstChunk_timesOut() {
        var wrapped = new StreamIdleTimeoutChatModel(
                model(Flux.concat(Flux.just(response("first")), Flux.never())), TIMEOUT);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> wrapped.stream(PROMPT).blockLast());

        assertTrue(ex.getMessage().contains("等待模型流数据超时"));
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
mvn -pl springai-code-tui -am \
  -Dtest=StreamIdleTimeoutChatModelTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because `StreamIdleTimeoutChatModel` does not exist.

- [ ] **Step 3: Implement the minimal stream timeout decorator**

Create `StreamIdleTimeoutChatModel.java`:

```java
package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/** Adds a first-item and between-item idle timeout to an LLM response stream. */
final class StreamIdleTimeoutChatModel implements ChatModel {
    private final ChatModel delegate;
    private final Duration idleTimeout;

    StreamIdleTimeoutChatModel(ChatModel delegate, Duration idleTimeout) {
        this.delegate = delegate;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt)
                .timeout(idleTimeout)
                .onErrorMap(TimeoutException.class, ignored -> new RuntimeException(
                        "等待模型流数据超时（" + formatSeconds(idleTimeout) + " 秒无新数据）。"
                                + "可通过 " + LlmTimeouts.READ_TIMEOUT_ENV + " 调整。"));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @SuppressWarnings("removal")
    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private static String formatSeconds(Duration duration) {
        long millis = duration.toMillis();
        return millis % 1_000 == 0 ? Long.toString(millis / 1_000)
                : Double.toString(millis / 1_000.0);
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: 2 tests pass, 0 failures, 0 errors.

- [ ] **Step 5: Add signal-preservation and non-total-timeout tests**

Add tests that verify:

```java
@Test
void regularChunksCanRunLongerThanOneTimeoutWindow() {
    Flux<ChatResponse> source = Flux.interval(Duration.ZERO, Duration.ofMillis(60))
            .take(4)
            .map(i -> response("chunk-" + i));
    var wrapped = new StreamIdleTimeoutChatModel(model(source), TIMEOUT);

    List<ChatResponse> values = wrapped.stream(PROMPT).collectList().block();

    assertNotNull(values);
    assertEquals(4, values.size());
}

@Test
void normalCompletionAndOriginalErrorArePreserved() {
    ChatResponse expected = response("ok");
    assertSame(expected, new StreamIdleTimeoutChatModel(model(Flux.just(expected)), TIMEOUT)
            .stream(PROMPT).blockLast());

    IllegalStateException boom = new IllegalStateException("boom");
    RuntimeException seen = assertThrows(RuntimeException.class,
            () -> new StreamIdleTimeoutChatModel(model(Flux.error(boom)), TIMEOUT)
                    .stream(PROMPT).blockLast());
    assertSame(boom, seen);
}

@Test
void downstreamCancellationPropagatesUpstreamWithoutTimeoutError() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicBoolean errored = new AtomicBoolean();
    Flux<ChatResponse> source = Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true));
    var disposable = new StreamIdleTimeoutChatModel(model(source), Duration.ofSeconds(5))
            .stream(PROMPT).doOnError(ignored -> errored.set(true)).subscribe();

    disposable.dispose();

    assertTrue(cancelled.get());
    assertFalse(errored.get());
}
```

Also add a test whose delegate returns distinct `call`, `getOptions`, and `getDefaultOptions` objects; assert all three are returned by identity from the wrapper.

- [ ] **Step 6: Run all decorator tests**

Run the command from Step 2.

Expected: all `StreamIdleTimeoutChatModelTest` tests pass. The periodic-stream test takes roughly 180 ms and proves the timeout is idle-based, not total-duration based.

- [ ] **Step 7: Commit the decorator**

```bash
git add \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModel.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutChatModelTest.java
git commit -m "$(cat <<'EOF'
fix: time out idle LLM streams

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
```

### Task 2: Provider-Wide Timeout Wiring

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProvider.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProviderTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java:148-169`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationThinkingConfigTest.java`

**Interfaces:**
- Consumes: `StreamIdleTimeoutChatModel(ChatModel, Duration)`, `LlmTimeouts.from(Function<String,String>)`, and existing `UsageRecordingProvider`.
- Produces: public `StreamIdleTimeoutProvider(LlmProvider inner, Duration idleTimeout)` and startup composition `UsageRecordingProvider(StreamIdleTimeoutProvider(provider, timeout), accumulator)`.

- [ ] **Step 1: Write failing provider decorator tests**

Create `StreamIdleTimeoutProviderTest.java` using a fake `LlmProvider`. Assert that:

```java
@Test
void chatModelIsTimeoutWrappedOnceAndCached() {
    var provider = new StreamIdleTimeoutProvider(INNER, Duration.ofMillis(50));

    ChatModel first = provider.chatModel();
    ChatModel second = provider.chatModel();

    assertInstanceOf(StreamIdleTimeoutChatModel.class, first);
    assertSame(first, second);
}

@Test
void providerMetadataAndOptionsDelegateByIdentity() {
    var provider = new StreamIdleTimeoutProvider(INNER, Duration.ofMillis(50));

    assertEquals(INNER.id(), provider.id());
    assertEquals(INNER.available(), provider.available());
    assertSame(INNER.options("model"), provider.options("model"));
    assertSame(INNER.models(), provider.models());
    assertEquals(INNER.defaultModel(), provider.defaultModel());
}
```

The fake provider must also expose fixed `ThinkingCapabilities`, `ThinkingConfig` options, and `ModelCapabilities`; assert those delegates too, matching `UsageRecordingProviderTest` coverage.

- [ ] **Step 2: Run the provider test and verify RED**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=StreamIdleTimeoutProviderTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because `StreamIdleTimeoutProvider` does not exist.

- [ ] **Step 3: Implement the provider decorator**

Create `StreamIdleTimeoutProvider.java` with the same forwarding surface as `UsageRecordingProvider`:

```java
public final class StreamIdleTimeoutProvider implements LlmProvider {
    private final LlmProvider inner;
    private final Duration idleTimeout;
    private volatile ChatModel timed;

    public StreamIdleTimeoutProvider(LlmProvider inner, Duration idleTimeout) {
        this.inner = inner;
        this.idleTimeout = idleTimeout;
    }

    @Override public String id() { return inner.id(); }
    @Override public boolean available() { return inner.available(); }

    @Override
    public ChatModel chatModel() {
        ChatModel model = timed;
        if (model == null) {
            model = new StreamIdleTimeoutChatModel(inner.chatModel(), idleTimeout);
            timed = model;
        }
        return model;
    }

    @Override public ChatOptions options(String modelId) { return inner.options(modelId); }
    @Override public ThinkingCapabilities thinkingCapabilities(String modelId) {
        return inner.thinkingCapabilities(modelId);
    }
    @Override public ChatOptions options(String modelId, ThinkingConfig config) {
        return inner.options(modelId, config);
    }
    @Override public List<ModelOption> models() { return inner.models(); }
    @Override public String defaultModel() { return inner.defaultModel(); }
    @Override public ModelCapabilities capabilities(String modelId) { return inner.capabilities(modelId); }
}
```

Include the imports used by `UsageRecordingProvider`, plus `java.time.Duration`.

- [ ] **Step 4: Run the provider test and verify GREEN**

Run the command from Step 2.

Expected: all provider decorator tests pass.

- [ ] **Step 5: Write a failing production-composition wiring test**

In `CodeTuiApplicationThinkingConfigTest`, add imports for the agent model/provider types, `Duration`, `Flux`, `List`, and assertions. Build a fake provider whose stream emits one response carrying usage and then hangs. Call the exact package-visible startup helper with a 100 ms timeout:

```java
@Test
void startupWrapperTimesOutHungStreamsAndRecordsSeenUsage() {
    var usage = new DefaultUsage(100, 20, 120, null, 80L, 0L);
    var metadata = ChatResponseMetadata.builder().usage(usage).build();
    var first = new ChatResponse(
            List.of(new Generation(new AssistantMessage("first"))), metadata);
    ChatModel hanging = new ChatModel() {
        @Override public ChatResponse call(Prompt prompt) { return first; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.concat(Flux.just(first), Flux.never());
        }
    };
    LlmProvider provider = new TestProvider(hanging);
    TokenUsageAccumulator accumulator = new TokenUsageAccumulator();
    LlmProvider wrapped = CodeTuiApplication.wrap(
            provider, accumulator, Duration.ofMillis(100));

    RuntimeException error = assertThrows(RuntimeException.class,
            () -> wrapped.chatModel().stream(new Prompt("hi")).blockLast());

    assertTrue(error.getMessage().contains("等待模型流数据超时"));
    assertEquals(100L, accumulator.snapshot().promptTokens(),
            "UsageRecordingProvider 必须在最外层，超时前看到的 usage 仍应提交");
}
```

Add a private `TestProvider` in that test file implementing `LlmProvider` with one `ModelOption`, default thinking/capability methods, and `chatModel()` returning the injected model. This verifies the exact production composition without reflection, a network request, or a 10-second sleep.

- [ ] **Step 6: Run the startup wiring test and verify RED**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiApplicationThinkingConfigTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: the new assertion fails because startup currently wraps only `UsageRecordingProvider`.

- [ ] **Step 7: Compose timeout inside usage recording at startup**

In `CodeTuiApplication.createProviderRegistry(...)`, resolve timeouts from the provided environment so tests and production use the same source:

```java
LlmTimeouts timeouts = LlmTimeouts.from(env::get);
```

Change the package-visible helper to accept the resolved duration directly:

```java
static LlmProvider wrap(LlmProvider provider, TokenUsageAccumulator accumulator, Duration idleTimeout) {
    return new UsageRecordingProvider(
            new StreamIdleTimeoutProvider(provider, idleTimeout),
            accumulator);
}
```

Pass `timeouts.readTimeout()` to every one of the six provider `wrap(...)` calls. Add `java.time.Duration` to the application imports. Keep `UsageRecordingProvider` outermost so timeout errors cross its `doOnTerminate` and commit the last observed usage.

- [ ] **Step 8: Run wiring and related decorator tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=CodeTuiApplicationThinkingConfigTest,UsageRecordingProviderTest,UsageRecordingChatModelTest,StreamIdleTimeoutProviderTest,StreamIdleTimeoutChatModelTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests pass with 0 failures and 0 errors.

- [ ] **Step 9: Commit provider wiring**

```bash
git add \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProvider.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/StreamIdleTimeoutProviderTest.java \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/CodeTuiApplicationThinkingConfigTest.java
git commit -m "$(cat <<'EOF'
fix: wire idle timeout across providers

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
```

### Task 3: End-to-End Error Recovery and Verification

**Files:**
- Modify if needed: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentSubmitErrorTest.java`
- Verify: all modified and existing timeout-related files.

**Interfaces:**
- Consumes: timeout error emitted by `StreamIdleTimeoutChatModel` and existing `CodingAgent.handleError()` / `ConversationState.onError()` behavior.
- Produces: regression evidence that a hung stream returns the conversation to `IDLE`.

- [ ] **Step 1: Add an exact agent-level recovery test**

Extend `CodingAgentSubmitErrorTest` with a real `ChatClient` backed by a timeout-wrapped hanging model. Override `getOptions()` with `OpenAiChatOptions` so `ChatClient` can build its prompt without touching a network provider:

```java
@Test
void asynchronousIdleTimeout_isReportedAndStateReset() throws Exception {
    ChatOptions options = OpenAiChatOptions.builder().model("gpt-5.6-sol").build();
    ChatModel hanging = new ChatModel() {
        @Override public ChatResponse call(Prompt prompt) { return null; }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.never(); }
        @Override public ChatOptions getOptions() { return options; }
    };
    ChatClient client = ChatClient.builder(
            new StreamIdleTimeoutChatModel(hanging, Duration.ofMillis(100))).build();
    ConversationState state = new ConversationState();
    CodingAgent agent = new CodingAgent(client, state, "s", new AtomicLong(), null, null, null);

    Disposable disposable = agent.submit("hi");
    long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
    while (!state.isIdle() && System.nanoTime() < deadline) {
        Thread.sleep(10);
    }

    assertTrue(state.isIdle(), "异步超时必须经 onError 把状态复位到 IDLE");
    assertTrue(state.drainPending().stream().anyMatch(line ->
            line.text().contains("等待模型流数据超时")));
    disposable.dispose();
}
```

Add imports for `ChatModel`, `ChatResponse`, `ChatOptions`, `Prompt`, `OpenAiChatOptions`, `Flux`, and `Duration`.

- [ ] **Step 2: Run the agent recovery test and verify it passes after Tasks 1-2**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=CodingAgentSubmitErrorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected after Tasks 1-2: the new recovery test passes. If it exposed a missing error/state transition, make only the smallest production adjustment required and rerun until green.

- [ ] **Step 3: Run all timeout-focused tests**

```bash
mvn -pl springai-code-tui -am \
  -Dtest=LlmTimeoutsTest,OpenAiTimeoutsTest,ProviderTimeoutRuntimeTest,StreamIdleTimeoutChatModelTest,StreamIdleTimeoutProviderTest,UsageRecordingChatModelTest,UsageRecordingProviderTest,CodingAgentSubmitErrorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all tests pass. `ProviderTimeoutRuntimeTest` still reports approximately two-second network timeouts; stream decorator tests complete in under a few seconds.

- [ ] **Step 4: Run the full module test suite**

```bash
mvn -pl springai-code-tui -am test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors.

- [ ] **Step 5: Run package verification**

```bash
mvn -pl springai-code-tui -am clean package
```

Expected: `BUILD SUCCESS` and a rebuilt `springai-code-tui/target/springai-code-tui-1.13.0.jar`.

- [ ] **Step 6: Inspect final diff and working tree**

```bash
git diff --check
git status --short
git log --oneline -5
```

Expected: no whitespace errors; only intentional implementation/test changes remain uncommitted. Do not modify or terminate the user's currently running code-tui processes during verification.

- [ ] **Step 7: Commit any agent-level regression test or final adjustments**

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentSubmitErrorTest.java
git commit -m "$(cat <<'EOF'
test: verify idle timeout restores conversation

Co-Authored-By: CodeTui <noreply@codetui.dev>
EOF
)"
```

If Step 1 required no file change because existing lower-level tests already prove the complete error path, skip this commit rather than creating an empty commit.

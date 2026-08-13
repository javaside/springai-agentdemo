package io.github.javaside.springai.codetui.agent.thinking;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingConfigStoreTest {

    @Test
    void providerAndModelAreIndependent(@TempDir Path root) {
        ThinkingConfigStore store = ThinkingConfigStore.load(root);
        store.put("openai", "same", ThinkingConfig.enabledEffort("high"));
        store.put("qwen", "same", ThinkingConfig.enabledBudget(4096));
        assertTrue(store.save());

        ThinkingConfigStore restored = ThinkingConfigStore.load(root);
        assertEquals("high", restored.get("openai", "same").effort());
        assertEquals(4096, restored.get("qwen", "same").thinkingBudget());
    }

    @Test
    void defaultRemovesPersistedEntry(@TempDir Path root) {
        ThinkingConfigStore store = ThinkingConfigStore.load(root);
        store.put("openai", "gpt", ThinkingConfig.enabledEffort("high"));
        store.put("openai", "gpt", ThinkingConfig.defaults());
        assertTrue(store.save());
        assertEquals(ThinkingConfig.defaults(), ThinkingConfigStore.load(root).get("openai", "gpt"));
    }

    @Test
    void missingFileIsEmptyAndSilent(@TempDir Path root) {
        Logger logger = (Logger) LoggerFactory.getLogger(ThinkingConfigStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Map.of(), ThinkingConfigStore.load(root).snapshot());
            assertTrue(appender.list.isEmpty());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void malformedJsonFallsBackToEmptyAndWarns(@TempDir Path root) throws Exception {
        Path file = ThinkingConfigStore.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{");
        Logger logger = (Logger) LoggerFactory.getLogger(ThinkingConfigStore.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertEquals(Map.of(), ThinkingConfigStore.load(root).snapshot());
            assertEquals(1, appender.list.size());
            assertTrue(appender.list.get(0).getFormattedMessage().contains("不是合法配置"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void unknownFieldsAreIgnored(@TempDir Path root) throws Exception {
        Path file = ThinkingConfigStore.fileFor(root);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"version":1,"future":true,"providers":{"openai":{"gpt":{"mode":"ENABLED","effort":"high","newField":7}}}}
                """);
        assertEquals(ThinkingConfig.enabledEffort("high"), ThinkingConfigStore.load(root).get("openai", "gpt"));
    }

    @Test
    void writeFailureReturnsFalseButKeepsMemory(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".codetui"), "not a directory");
        ThinkingConfigStore store = ThinkingConfigStore.load(root);
        ThinkingConfig config = ThinkingConfig.enabledEffort("high");
        store.put("openai", "gpt", config);
        assertFalse(store.save());
        assertEquals(config, store.get("openai", "gpt"));
    }

    @Test
    void snapshotIsDeeplyImmutable() {
        ThinkingConfigStore store = ThinkingConfigStore.inMemory();
        store.put("openai", "gpt", ThinkingConfig.enabledEffort("high"));
        Map<String, Map<String, ThinkingConfig>> snapshot = store.snapshot();
        assertFalse(snapshot.isEmpty());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.get("openai").put("other", ThinkingConfig.disabled()));
    }

    @Test
    void successfulWriteLeavesNoTempFiles(@TempDir Path root) throws Exception {
        ThinkingConfigStore store = ThinkingConfigStore.load(root);
        store.put("qwen", "q", ThinkingConfig.enabledBudget(2048));
        assertTrue(store.save());
        try (var paths = Files.list(root.resolve(".codetui"))) {
            List<String> leftovers = paths.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".tmp"))
                    .toList();
            assertEquals(List.of(), leftovers);
        }
    }
}

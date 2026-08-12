package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class InlineRenderBatchTest {

    @Test
    void patchDisplayExposesBatchMethods() throws Exception {
        Class<?> display = Class.forName("dev.tamboui.inline.InlineDisplay");
        assertNotNull(display.getMethod("beginPrintBatch"));
        assertNotNull(display.getMethod("endPrintBatch"));
    }

    @Test
    void openWithoutStartedRunnerIsSafeNoop() throws Exception {
        try (AutoCloseable ignored = InlineRenderBatch.open(null)) {
            assertNotNull(ignored);
        }
    }
}

package io.github.javaside.springai.codetui.ui;

import dev.tamboui.toolkit.app.InlineToolkitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opens the patched InlineDisplay scrollback batch without leaking reflection into the view. */
final class InlineRenderBatch {
    private static final Logger log = LoggerFactory.getLogger(InlineRenderBatch.class);
    private static final AutoCloseable NOOP = () -> { };

    private InlineRenderBatch() {
    }

    static AutoCloseable open(InlineToolkitRunner runner) {
        if (runner == null) return NOOP;
        try {
            Object tui = runner.tuiRunner();
            Object viewport = get(tui, "viewport");
            Object display = get(viewport, "display");
            Method begin = display.getClass().getMethod("beginPrintBatch");
            Method end = display.getClass().getMethod("endPrintBatch");
            begin.invoke(display);
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                try {
                    end.invoke(display);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    log.debug("关闭行内打印批次失败，已降级", e);
                }
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            log.debug("行内打印批次不可用，退回逐行打印", e);
            return NOOP;
        }
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}

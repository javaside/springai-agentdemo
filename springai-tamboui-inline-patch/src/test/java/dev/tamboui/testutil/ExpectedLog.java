package dev.tamboui.testutil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 断言式日志接管（JUL）：故障注入用例「预期会出现」的 SEVERE/WARNING 不再泼进构建输出。
 *
 * <p><b>纪律：预期噪音必须被断言，而不是被静音。</b>单纯 {@code setUseParentHandlers(false)}
 * 一关了之会让输出「干净」，但真实意外也会被同一开关吞掉——输出里再无警报，人却已学会无视它。
 * 断言式接管两头都保住：
 *
 * <ul>
 *   <li>{@link #capture}：给被测 logger 挂收集 Handler 并切断父级输出——预期日志不外泄；</li>
 *   <li>{@link #awaitRecord}：轮询等待一条级别 + 消息匹配的记录（日志多由写线程在状态标志
 *       <i>之后</i>异步 publish，如 {@code markDead} 先置 dead 后打 WARNING，观测到状态时
 *       记录可能尚未落网，故必须带超时轮询），命中即标记已断言并返回，thrown 在返回值上另行断言；</li>
 *   <li>{@link #close}：兜底绊线——捕获到 WARNING 及以上却未被断言的记录直接判测试失败，
 *       防「静音吞掉真异常」与「新用例忘了断言」两种漂移。INFO 及以下不绊（本就安静，也非警报）。</li>
 * </ul>
 *
 * <p>未 capture 的 logger 照常走全局输出：测试输出里再出现的 SEVERE/WARNING 都值得当真。
 */
public final class ExpectedLog implements AutoCloseable {

    /** 一条被捕获的记录 + 是否已被 {@link #awaitRecord} 断言过（绊线依据）。 */
    private static final class Captured {
        private final LogRecord record;
        private volatile boolean asserted;

        Captured(LogRecord record) {
            this.record = record;
        }
    }

    private final Logger logger;
    private final Handler handler;
    private final boolean useParentHandlers;
    private final List<Captured> records;
    private boolean closed;

    private ExpectedLog(Logger logger, Handler handler, boolean useParentHandlers, List<Captured> records) {
        this.logger = logger;
        this.handler = handler;
        this.useParentHandlers = useParentHandlers;
        this.records = records;
    }

    /** 接管 {@code loggingClass} 的同名 logger（主代码 logger 均以类名命名）。 */
    public static ExpectedLog capture(Class<?> loggingClass) {
        return capture(Logger.getLogger(loggingClass.getName()));
    }

    public static ExpectedLog capture(Logger logger) {
        boolean useParentHandlers = logger.getUseParentHandlers();
        List<Captured> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(new Captured(record));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        return new ExpectedLog(logger, handler, useParentHandlers, records);
    }

    /**
     * 轮询等待一条「级别相等 + 消息含 {@code messageContains}」且未被断言过的记录。
     *
     * @return 命中的记录（{@code getThrown()} 在其上另行断言）
     * @throws AssertionError 超时未命中——被断言的日志契约没有发生
     */
    public LogRecord awaitRecord(Level level, String messageContains, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            for (Captured captured : records) {
                if (!captured.asserted && level.equals(captured.record.getLevel())
                        && captured.record.getMessage() != null
                        && captured.record.getMessage().contains(messageContains)) {
                    captured.asserted = true;
                    return captured.record;
                }
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("等待 " + timeout + " " + unit + " 未出现 " + level + " 且消息含 \""
                + messageContains + "\" 的日志记录（被断言的日志契约没有发生）");
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        logger.removeHandler(handler);
        logger.setUseParentHandlers(useParentHandlers);
        List<String> unasserted = new ArrayList<>();
        for (Captured captured : records) {
            if (!captured.asserted && captured.record.getLevel().intValue() >= Level.WARNING.intValue()) {
                unasserted.add(captured.record.getLevel() + " " + captured.record.getMessage()
                        + (captured.record.getThrown() == null ? "" : " (" + captured.record.getThrown() + ")"));
            }
        }
        if (!unasserted.isEmpty()) {
            throw new AssertionError("捕获到未断言的 WARNING+ 日志——预期噪音必须用 awaitRecord 断言，"
                    + "不得任其落网: " + unasserted);
        }
    }
}

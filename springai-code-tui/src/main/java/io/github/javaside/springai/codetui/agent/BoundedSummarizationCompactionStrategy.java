package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.session.SessionTokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Compacts by token budget. Two modes:
 *
 * <p><b>校准模式</b>(新构造器,生产装配用):乐观全量摘要 + 区间学习。窗口配置正确时归档一次全量
 * 送摘要模型(1 次调用);超限失败时按错误里的窗口数字(或减半探测)自我校准,校准区间按
 * {@code provider:model} 记在共享 {@link CalibrationState} 里,跨压缩记忆。三重安全阀 + 全局
 * {@value #MAX_TOTAL_CALLS} 次调用硬上限,任一触发即落本地纯文本兜底(localDigest)。
 *
 * <p><b>悲观模式</b>(旧构造器,保留给测试与无校准场景):固定 chunk 预算预分片,行为与历史版本
 * 逐行一致。
 *
 * <p>The newest complete suffix is kept verbatim; older events are summarized.
 */
final class BoundedSummarizationCompactionStrategy implements CompactionStrategy {

    private static final Logger log = LoggerFactory.getLogger(BoundedSummarizationCompactionStrategy.class);

    private static final String SUMMARY_SOURCE = "bounded-recursive-summarization";

    // —— 校准模式常量(设计 v2:安全阀 + 全局上限;边界统一为「触线前允许最后一次尝试」)——
    /** 安全阀 1:切块/探测预算<b>严格小于</b>此值即本地兜底;恰为 16k 允许最后一次尝试。 */
    static final long MIN_SUMMARY_BUDGET = 16_000L;
    /** 安全阀 2:按预算切出的块数<b>超过</b>此值即本地兜底(等效回到旧算法的慢,不如不发)。 */
    static final int MAX_CHUNKS = 8;
    /** 安全阀 3:单次压缩内探测减半深度上限(第 4 次减半允许尝试,仍失败才兜底)。 */
    static final int MAX_HALVING_DEPTH = 4;
    /** 全局硬上限:单次压缩内所有模型调用(全量+探测+切块+再压缩)共享一个递减计数,归零即兜底。 */
    static final int MAX_TOTAL_CALLS = 20;

    /**
     * 一次快照派生的模型身份与窗口:校准 key 与预算必须<b>同源</b>——由装配方在单次
     * {@code ProviderRegistry.activeRequestSelection()} 里派生,防 /model 并发切换在两次读取间交错。
     */
    record ModelSnapshot(String calibrationKey, long windowTokens, long inputReserve) {
        long fullInputBudget() { return Math.max(1L, windowTokens - inputReserve); }
    }

    /** 全局调用预算耗尽的内部信号:调用方一律转本地兜底。必须先于 RuntimeException 被 catch。 */
    private static final class CallBudgetExhausted extends RuntimeException {
        CallBudgetExhausted() { super(null, null, false, false); }
    }

    private final LongSupplier targetTokens;
    private final LongSupplier chunkTokens;          // 悲观模式专用;校准模式下为 null
    private final ToIntFunction<String> estimator;
    private final Function<String, String> summarizer;
    private final Supplier<ModelSnapshot> snapshot;  // 校准模式专用;悲观模式下为 null
    private final CalibrationState calibration;      // 非 null 即校准模式
    private final int maxEventsToKeep;              // 0 = 按 token 预算保留; >0 = 强制保留最近 N 个事件

    /** 悲观模式(测试兼容)。 */
    BoundedSummarizationCompactionStrategy(long targetTokens, long chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this(() -> targetTokens, () -> chunkTokens, estimator, summarizer, 0);
    }

    /** 悲观模式(测试兼容):固定 chunk 预算预分片,无校准能力。 */
    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer) {
        this(targetTokens, chunkTokens, estimator, summarizer, 0);
    }

    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer,
                                           int maxEventsToKeep) {
        this(targetTokens, chunkTokens, estimator, summarizer, null, null, maxEventsToKeep);
    }

    /** 校准模式(生产装配):乐观全量 + 区间学习。auto/manual 两条策略须共享同一个 calibration。 */
    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer,
                                           Supplier<ModelSnapshot> snapshot,
                                           CalibrationState calibration) {
        this(targetTokens, estimator, summarizer, snapshot, calibration, 0);
    }

    BoundedSummarizationCompactionStrategy(LongSupplier targetTokens,
                                           ToIntFunction<String> estimator,
                                           Function<String, String> summarizer,
                                           Supplier<ModelSnapshot> snapshot,
                                           CalibrationState calibration,
                                           int maxEventsToKeep) {
        this(targetTokens, null, estimator, summarizer,
                Objects.requireNonNull(snapshot, "snapshot"),
                Objects.requireNonNull(calibration, "calibration"),
                maxEventsToKeep);
    }

    private BoundedSummarizationCompactionStrategy(LongSupplier targetTokens, LongSupplier chunkTokens,
                                                   ToIntFunction<String> estimator,
                                                   Function<String, String> summarizer,
                                                   Supplier<ModelSnapshot> snapshot,
                                                   CalibrationState calibration,
                                                   int maxEventsToKeep) {
        this.targetTokens = targetTokens;
        this.chunkTokens = chunkTokens;
        this.estimator = estimator;
        this.summarizer = summarizer;
        this.snapshot = snapshot;
        this.calibration = calibration;
        this.maxEventsToKeep = maxEventsToKeep;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        List<SessionEvent> events = request.events();
        long targetBudget = targetTokens.getAsLong();
        if (targetBudget <= 0) throw new IllegalStateException("token budgets must be positive");
        if (calibration == null && chunkTokens.getAsLong() <= 0) {
            throw new IllegalStateException("token budgets must be positive");
        }
        long total = SessionTokenEstimator.estimateEvents(events, estimator);
        boolean forceByEventCount = maxEventsToKeep > 0 && events.size() > maxEventsToKeep;
        if (!forceByEventCount && total <= targetBudget) {
            return new CompactionResult(events, List.of(), 0);
        }

        EventSplit split = splitEvents(events, targetBudget);
        return calibration == null
                ? compactPessimistic(request, split.archived(), split.kept(), targetBudget, total)
                : compactCalibrated(request, split.archived(), split.kept(), targetBudget, total);
    }

    // ==================== 校准模式 ====================

    private CompactionResult compactCalibrated(CompactionRequest request, List<SessionEvent> archived,
                                               List<SessionEvent> kept, long targetBudget, long total) {
        long summaryBudget = Math.max(1L, targetBudget
                - SessionTokenEstimator.estimateEvents(kept, estimator)
                - estimate("[Earlier conversation summary]\n") - 8L);
        String text = format(archived);
        long textEstimate = estimate(text);
        ModelSnapshot snap = snapshot.get();   // 一次快照:key 与窗口同源
        int[] callsLeft = { MAX_TOTAL_CALLS };

        String merged = summarizeCalibrated(text, textEstimate, snap, callsLeft, summaryBudget);
        if (merged == null || estimate(merged) > summaryBudget) {
            merged = localDigest(archived, summaryBudget);
        }
        log.debug("压缩完成: E={}, 调用数={}, 兜底={}", textEstimate,
                MAX_TOTAL_CALLS - callsLeft[0], merged.startsWith("Earlier history was too large"));

        List<SessionEvent> compacted = new ArrayList<>(kept.size() + 2);
        compacted.add(synthetic(request, new UserMessage("[Earlier conversation summary]")));
        compacted.add(synthetic(request, new AssistantMessage(merged)));
        compacted.addAll(kept);
        int saved = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                total - SessionTokenEstimator.estimateEvents(compacted, estimator)));
        return new CompactionResult(compacted, archived, saved);
    }

    /** 主算法:乐观全量 → 区间学习 → 切块;返回 null 即「走本地兜底」。 */
    private String summarizeCalibrated(String text, long textEstimate, ModelSnapshot snap,
                                       int[] callsLeft, long summaryBudget) {
        String key = snap.calibrationKey();
        CalibrationState.Interval interval = calibration.get(key);
        long fullBudget = snap.fullInputBudget();

        boolean provenSafe = interval.goodProven() && textEstimate <= interval.knownGood();
        boolean middleZone = textEstimate <= fullBudget
                && (interval.knownBad() == null || textEstimate < interval.knownBad());
        long chunkBudget;
        if (provenSafe || middleZone) {
            // 1+3. 已证明安全,或区间中间态/首次:乐观全量(失败便宜且能学到容量)
            try {
                String summary = call(text, callsLeft);
                calibration.recordGood(key, textEstimate);
                log.debug("压缩全量摘要成功: E={}", textEstimate);
                return recompress(summary, fullBudget, callsLeft, summaryBudget);
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (!SummarizerOverflow.isOverflow(failure)) {
                    log.debug("压缩全量摘要非超限失败,本地兜底: {}", failure.toString());
                    return null;
                }
                calibration.recordBad(key, textEstimate);
                interval = calibration.get(key);
                Long parsed = SummarizerOverflow.parseWindowTokens(failure);
                if (parsed != null && parsed - snap.inputReserve() > 0) {
                    // 带数字:官方容量,一步到位,不需要探测
                    chunkBudget = clamp(parsed - snap.inputReserve(), interval.provenFloor(), fullBudget);
                    log.debug("压缩全量超限,错误带窗口数字 {} → 切块预算 {}", parsed, chunkBudget);
                } else {
                    Long probed = probeForBudget(text, textEstimate, key, fullBudget, callsLeft);
                    if (probed == null) return null;
                    chunkBudget = probed;
                }
            }
        } else if (interval.knownBad() != null && textEstimate >= interval.knownBad()) {
            // 2. knownBad 短路:绝不重发注定失败的全量
            chunkBudget = Math.max(interval.knownGood(),
                    Math.min(CalibrationState.SAFE_FALLBACK_BUDGET, interval.knownBad() - 1));
            log.debug("压缩 knownBad 短路: E={} ≥ knownBad={} → 切块预算 {}",
                    textEstimate, interval.knownBad(), chunkBudget);
        } else {
            // E > fullBudget:归档比配置窗口还大,按窗口预算直接切块(配置诚实则必成,虚高则学到后短路)
            chunkBudget = interval.knownBad() != null
                    ? Math.max(1L, Math.min(fullBudget, interval.knownBad() - 1))
                    : fullBudget;
        }
        return summarizeChunked(text, textEstimate, chunkBudget, key, callsLeft, summaryBudget);
    }

    /**
     * 无数字路径的减半探测:单次请求、输入取全文前 budget tokens 的前缀。
     * 探测成功只学容量(结果不复用),返回该预算;失败继续减半,深度 ≤ {@value #MAX_HALVING_DEPTH}。
     */
    private Long probeForBudget(String text, long textEstimate, String key,
                                long fullBudget, int[] callsLeft) {
        long budget = textEstimate;
        for (int depth = 1; depth <= MAX_HALVING_DEPTH; depth++) {
            CalibrationState.Interval interval = calibration.get(key);
            long badCap = interval.knownBad() == null ? Long.MAX_VALUE : interval.knownBad() - 1;
            budget = clamp(Math.min(budget / 2, badCap), interval.provenFloor(), fullBudget);
            if (budget < MIN_SUMMARY_BUDGET) {
                log.debug("压缩探测预算 {} 跌破下限 {},本地兜底", budget, MIN_SUMMARY_BUDGET);
                return null;
            }
            String probe = text.substring(0, prefixEnd(text, 0, budget));
            try {
                call(probe, callsLeft);   // 结果只学容量,不作为摘要复用
                calibration.recordGood(key, budget);
                log.debug("压缩探测成功: budget={}, depth={}", budget, depth);
                return budget;
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (!SummarizerOverflow.isOverflow(failure)) {
                    log.debug("压缩探测非超限失败,本地兜底: {}", failure.toString());
                    return null;
                }
                calibration.recordBad(key, budget);
            }
        }
        log.debug("压缩探测减半深度耗尽({}),本地兜底", MAX_HALVING_DEPTH);
        return null;
    }

    /** 按校准预算正式切块;任一块失败即整体兜底(不复用部分摘要,超限块仍记入 knownBad)。 */
    private String summarizeChunked(String text, long textEstimate, long chunkBudget, String key,
                                    int[] callsLeft, long summaryBudget) {
        if (chunkBudget < MIN_SUMMARY_BUDGET) {
            log.debug("压缩切块预算 {} 跌破下限 {},本地兜底", chunkBudget, MIN_SUMMARY_BUDGET);
            return null;
        }
        long chunkCount = (textEstimate + chunkBudget - 1) / chunkBudget;
        if (chunkCount > MAX_CHUNKS) {
            log.debug("压缩切块数 {} 超上限 {},本地兜底", chunkCount, MAX_CHUNKS);
            return null;
        }
        List<String> summaries = new ArrayList<>();
        for (String chunk : textChunks(text, chunkBudget)) {
            String summary;
            try {
                summary = call(chunk, callsLeft);
            } catch (CallBudgetExhausted exhausted) {
                return null;
            } catch (RuntimeException failure) {
                if (SummarizerOverflow.isOverflow(failure)) {
                    // 中途某块超限:记下失败量防下次原样再撞;本轮不复用部分摘要、不重试单块
                    calibration.recordBad(key, estimate(chunk));
                }
                log.debug("压缩切块失败,本地兜底: {}", failure.toString());
                return null;
            }
            if (summary != null && !summary.isBlank()) summaries.add(summary.strip());
            calibration.recordGood(key, estimate(chunk));   // 成功块逐个入账(诚实口径:按实际输入量)
        }
        if (summaries.isEmpty()) return null;
        return recompress(String.join("\n\n", summaries), chunkBudget, callsLeft, summaryBudget);
    }

    /** 合并摘要超预算时的再压缩循环(≤4 轮,调用数计入全局上限);返回 null 即「走本地兜底」。 */
    private String recompress(String merged, long regroupBudget, int[] callsLeft, long summaryBudget) {
        try {
            for (int round = 0; estimate(merged) > summaryBudget && round < 4; round++) {
                List<String> next = new ArrayList<>();
                for (String group : textChunks(merged, regroupBudget)) {
                    String summary = call(group, callsLeft);
                    if (summary != null && !summary.isBlank()) next.add(summary.strip());
                }
                String candidate = String.join("\n\n", next);
                if (candidate.isEmpty() || estimate(candidate) >= estimate(merged)) break;
                merged = candidate;
            }
        } catch (CallBudgetExhausted exhausted) {
            return null;
        } catch (RuntimeException failure) {
            log.debug("压缩再压缩失败,本地兜底: {}", failure.toString());
            return null;
        }
        return merged;
    }

    /** 全局调用上限的唯一扣减点:所有摘要模型调用必须走这里。 */
    private String call(String input, int[] callsLeft) {
        if (callsLeft[0] <= 0) {
            log.debug("压缩调用预算耗尽({} 次),本地兜底", MAX_TOTAL_CALLS);
            throw new CallBudgetExhausted();
        }
        callsLeft[0]--;
        return summarizer.apply(input);
    }

    private static long clamp(long value, long lower, long upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    // ==================== 悲观模式(与历史行为逐行一致) ====================

    private CompactionResult compactPessimistic(CompactionRequest request, List<SessionEvent> archived,
                                                List<SessionEvent> kept, long targetBudget, long total) {
        long chunkBudget = chunkTokens.getAsLong();
        List<String> summaries = new ArrayList<>();
        boolean summarizationFailed = false;
        try {
            for (List<SessionEvent> chunk : chunks(archived, chunkBudget)) {
                for (String bounded : textChunks(format(chunk), chunkBudget)) {
                    String summary = summarizer.apply(bounded);
                    if (summary != null && !summary.isBlank()) summaries.add(summary.strip());
                }
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }

        String merged = summarizationFailed || summaries.isEmpty()
                ? localDigest(archived, Math.max(1L, targetBudget / 2L))
                : String.join("\n\n", summaries);
        try {
            for (int round = 0; !summarizationFailed && estimate(merged) > chunkBudget && round < 4; round++) {
                List<String> next = new ArrayList<>();
                for (String group : textChunks(merged, chunkBudget)) {
                    String summary = summarizer.apply(group);
                    if (summary != null && !summary.isBlank()) next.add(summary.strip());
                }
                String candidate = String.join("\n\n", next);
                if (candidate.isEmpty() || estimate(candidate) >= estimate(merged)) break;
                merged = candidate;
            }
        } catch (RuntimeException failure) {
            summarizationFailed = true;
        }
        long summaryBudget = Math.max(1L, targetBudget
                - SessionTokenEstimator.estimateEvents(kept, estimator)
                - estimate("[Earlier conversation summary]\n") - 8L);
        if (estimate(merged) > summaryBudget) {
            merged = localDigest(archived, summaryBudget);
        }

        List<SessionEvent> compacted = new ArrayList<>(kept.size() + 2);
        compacted.add(synthetic(request, new UserMessage("[Earlier conversation summary]")));
        compacted.add(synthetic(request, new AssistantMessage(merged)));
        compacted.addAll(kept);
        int saved = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                total - SessionTokenEstimator.estimateEvents(compacted, estimator)));
        return new CompactionResult(compacted, archived, saved);
    }

    // ==================== 共用工具 ====================

    private record EventSplit(List<SessionEvent> archived, List<SessionEvent> kept) { }

    private EventSplit splitEvents(List<SessionEvent> events, long targetBudget) {
        if (maxEventsToKeep > 0 && events.size() > maxEventsToKeep) {
            int split = events.size() - maxEventsToKeep;
            while (split > 0 && !events.get(split).isRootEvent()) split--;
            if (split <= 0) split = events.size() == 1 ? 1 : Math.max(1, events.size() - 1);
            return new EventSplit(List.copyOf(events.subList(0, split)),
                    List.copyOf(events.subList(split, events.size())));
        }

        long keepBudget = Math.max(1L, targetBudget / 2L);
        int split = newestSuffixStart(events, keepBudget);
        if (split <= 0) {
            split = events.size() == 1 ? 1 : Math.max(1, events.size() - 1);
        }
        List<SessionEvent> archived = List.copyOf(events.subList(0, split));
        List<SessionEvent> kept = List.copyOf(events.subList(split, events.size()));
        if (SessionTokenEstimator.estimateEvents(kept, estimator) > keepBudget) {
            archived = events;
            kept = List.of();
        }
        return new EventSplit(archived, kept);
    }

    private int newestSuffixStart(List<SessionEvent> events, long budget) {
        long used = 0L;
        int start = events.size();
        for (int i = events.size() - 1; i >= 0; i--) {
            long eventTokens = SessionTokenEstimator.estimateEvents(List.of(events.get(i)), estimator);
            if (start < events.size() && used + eventTokens > budget) break;
            used += eventTokens;
            start = i;
        }
        while (start < events.size() && start > 0 && !events.get(start).isRootEvent()) start--;
        return start;
    }

    private List<List<SessionEvent>> chunks(List<SessionEvent> events, long chunkBudget) {
        List<List<SessionEvent>> out = new ArrayList<>();
        List<SessionEvent> current = new ArrayList<>();
        long used = 0L;
        for (SessionEvent event : events) {
            long tokens = SessionTokenEstimator.estimateEvents(List.of(event), estimator);
            if (!current.isEmpty() && used + tokens > chunkBudget) {
                out.add(List.copyOf(current));
                current.clear();
                used = 0L;
            }
            if (tokens > chunkBudget) {
                for (String part : textChunks(format(List.of(event)), chunkBudget)) {
                    out.add(List.of(syntheticEvent(event.getSessionId(), new UserMessage(part))));
                }
            } else {
                current.add(event);
                used += tokens;
            }
        }
        if (!current.isEmpty()) out.add(List.copyOf(current));
        return out;
    }

    /** 从 from 起、estimate ≤ budget 的最长前缀终点(二分)。 */
    private int prefixEnd(String text, int from, long budget) {
        int low = from + 1, high = text.length(), best = from + 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (estimate(text.substring(from, mid)) <= budget) {
                best = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        return best;
    }

    private List<String> textChunks(String text, long budget) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from < text.length()) {
            int end = prefixEnd(text, from, budget);
            out.add(text.substring(from, end));
            from = end;
        }
        return out;
    }

    private long estimate(String text) {
        return text == null || text.isEmpty() ? 0L : estimator.applyAsInt(text);
    }

    private String localDigest(List<SessionEvent> archived, long budget) {
        String prefix = "Earlier history was too large for a safe model-generated summary. "
                + archived.size() + " events were compacted locally. Recent context follows.";
        if (estimate(prefix) <= budget) return prefix;
        int low = 0, high = prefix.length(), best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (estimate(prefix.substring(0, mid)) <= budget) {
                best = mid;
                low = mid + 1;
            } else high = mid - 1;
        }
        return prefix.substring(0, best);
    }

    private String format(List<SessionEvent> events) {
        StringBuilder out = new StringBuilder();
        for (SessionEvent event : events) {
            Message message = event.getMessage();
            out.append('[').append(message.getMessageType()).append("] ");
            if (message instanceof ToolResponseMessage tool) {
                for (ToolResponseMessage.ToolResponse response : tool.getResponses()) {
                    out.append(response.name()).append(": ").append(response.responseData()).append('\n');
                }
            } else {
                out.append(message.getText()).append('\n');
                if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                    for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                        out.append("tool_call ").append(call.name()).append(' ').append(call.arguments()).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    private SessionEvent synthetic(CompactionRequest request, Message message) {
        return syntheticEvent(request.session().id(), message);
    }

    private static SessionEvent syntheticEvent(String sessionId, Message message) {
        return SessionEvent.builder().sessionId(sessionId).message(message)
                .metadata(Map.of(SessionEvent.METADATA_SYNTHETIC, true,
                        SessionEvent.METADATA_COMPACTION_SOURCE, SUMMARY_SOURCE))
                .build();
    }
}

package io.github.javaside.springai.codetui.agent.llm;

import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智谱 Coding Plan 限额错误识别与重置时刻解析（spec §3.1，纯函数）。
 *
 * <p><b>判据</b>：cause 链上的 {@link OpenAIServiceException} 且 {@code statusCode()==429} 且
 * （业务码 ∈ {@link #QUOTA_CODES} 或 message 双关键词兜底「使用上限」+「重置」——单关键词会把
 * 同走 openai-java 栈的 Qwen 等家的中文 429 误判进来）。Spring WCRE 路径刻意不识别（provider 中立）。
 * 1113（欠费）/1309（套餐过期）/1311（权限）不在集合内——等到天亮也不自愈，不等（spec §1.1）。
 *
 * <p><b>SII 穿透</b>：{@link StreamInterruptedException} message 置空但 cause 保留原始 429——
 * 沿 cause 链可命中（L2 路径）。
 *
 * <p><b>观测钩子</b>：命中即 WARN（业务码 + resetAt + message 原文）——真实 message 格式无真机
 * 样本（限额错误无法主动构造），首个真实命中后凭日志校准解析规则（spec §3.1）。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class QuotaLimitDetector {

    private static final Logger log = LoggerFactory.getLogger(QuotaLimitDetector.class);

    /** 限额业务码（docs.bigmodel.cn 错误码文档 2026-09-22 核实）。 */
    static final Set<String> QUOTA_CODES =
            Set.of("1308", "1310", "1316", "1317", "1318", "1319", "1320", "1321");

    /** 无偏移时间的解释时区：bigmodel.cn 国内站按北京时间。 */
    static final ZoneId ZHIPU_ZONE = ZoneId.of("Asia/Shanghai");

    /** `yyyy-MM-dd HH:mm[:ss]`（文档形态带反引号包裹；兼容 T 分隔）。 */
    private static final Pattern LOCAL_DT =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})[ T](\\d{2}:\\d{2})(?::(\\d{2}))?");
    /** ISO-8601 带偏移/Z（自带时区语义；逗号毫秒兼容）。 */
    private static final Pattern ISO_DT = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})");

    private QuotaLimitDetector() { }

    /** 识别限额错误并解析重置时刻；非限额（含普通限流 1302/1305、WCRE 429）返回 empty。 */
    public static Optional<QuotaLimit> detect(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof OpenAIServiceException svc && svc.statusCode() == 429) {
                String code = svc.code().orElse(null);
                String message = svc.getMessage();
                boolean codeHit = code != null && QUOTA_CODES.contains(code);
                boolean keywordHit = message != null && message.contains("使用上限") && message.contains("重置");
                if (codeHit || keywordHit) {
                    Instant resetAt = parseResetAt(message);
                    String effectiveCode = code != null ? code : "keyword";
                    log.warn("智谱限额错误：code={} resetAt={} message={}", effectiveCode, resetAt, message);
                    return Optional.of(resetAt != null
                            ? new QuotaLimit(effectiveCode, resetAt)
                            : QuotaLimit.withoutResetAt(effectiveCode));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从 message 全文解析重置时刻（不依赖措辞，只认时间模式）：ISO-8601 带偏移优先
     * （自带时区），其次 `yyyy-MM-dd HH:mm[:ss]` 按 {@link #ZHIPU_ZONE}；均失败返回 null
     * （调用方退化普通 429 行为——spec §4 安全网）。取<b>首个</b>匹配（message 只有一个时刻）。
     */
    static Instant parseResetAt(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        Matcher iso = ISO_DT.matcher(message);
        if (iso.find()) {
            String v = iso.group().replace(',', '.');
            // '+' 偏移无冒号形态（+0800）补冒号，Instant.parse 只认 +08:00
            v = v.replaceAll("([+-]\\d{2})(\\d{2})$", "$1:$2");
            try {
                return Instant.parse(v);
            } catch (Exception ignore) {
                // 落到本地格式再试
            }
        }
        Matcher m = LOCAL_DT.matcher(message);
        if (m.find()) {
            try {
                // 整段匹配即 "yyyy-MM-dd HH:mm[:ss]"（[ T] 两分隔都认），按有无秒位选 formatter，
                // 一次 parse 到 LocalDateTime（拆 LocalDate/LocalTime 再拼的写法在日期-only 文本上
                // LocalDateTime.parse(LocalDate...) 必抛 DateTimeException——勿走回头路）
                LocalDateTime ldt = LocalDateTime.parse(m.group(),
                        m.group(3) == null
                                ? DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                : DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return ldt.atZone(ZHIPU_ZONE).toInstant();
            } catch (Exception ignore) {
                // fail-open：返回 null，调用方退化
            }
        }
        return null;
    }
}

package io.github.javaside.springai.codetui.agent.media;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.compaction.CompactionRequest;
import org.springframework.ai.session.compaction.CompactionResult;
import org.springframework.ai.session.compaction.CompactionStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装饰任意 {@link CompactionStrategy}：把<b>被摘要掉的</b>事件里的文件/图片引用逐字捞出来，
 * 作为一份「会话附件清单」插回压缩结果的最前面。
 *
 * <p><b>它修的是什么</b>：会话里图片永远只以结构化引用块的形式存在，模型想看某张图就
 * {@code Read} 引用里的 {@code path}——<b>path 是唯一的寻址依据</b>。而压缩会把更早的事件
 * 交给 LLM 摘要，于是那些引用块几乎必然被改写成「用户提供了三张截图」之类的自然语言：
 * sha 路径一丢，图还躺在磁盘上，模型和用户却再也寻址不到了。清单把寻址信息<b>绕过 LLM</b>
 * 逐字搬运过去，摘要怎么改写都不影响它。
 *
 * <p><b>三条关键设计</b>：
 * <ol>
 *   <li><b>从 {@link CompactionResult#archivedEvents()} 捞，不是从 {@link CompactionRequest#events()}</b>。
 *       没被压掉的引用<b>还在原处</b>，从请求侧捞会把它们再列一遍——清单于是随每次压缩膨胀，
 *       而且和原文说的是同一件事。</li>
 *   <li><b>清单事件必须带 {@link VisionMaterializer#SYNTHETIC_KEY} 标记</b>。它是一条新造的
 *       user 消息；不标记的话，某些压缩形态下（清单插在最前、其后再无真实 user 消息）它会成为
 *       「最后一条非合成 UserMessage」，把出站兑现的<b>回合锚点带偏</b>——此后当轮一张图都不兑现，
 *       不报错不崩，只是答得变差。标记写在 <b>Message</b> 的 metadata 上，因为
 *       {@link VisionMaterializer#isSynthetic(Message)} 判的是消息不是事件。</li>
 *   <li><b>保留最近的、丢弃最早的，并如实说明丢了多少</b>。不封顶的话，长会话里这张清单自己
 *       就成了新的上下文问题；而丢了不说，模型会以为清单是全集，把「找不到就是没有过」当真。</li>
 * </ol>
 *
 * <p><b>为什么不用 {@link FileReferenceParser}</b>：那个解析器要求文件<b>真实存在</b>且在 root 内
 * ——它是兑现前的安全边界，严格是对的。但压缩场景下我们只是在<b>搬运文本</b>，不读任何字节：
 * artifact 可能已被 GC 清掉，可把它列进清单仍有价值（至少告诉模型「曾经有过、现在取不到了」）。
 * 故这里自己做一次宽松的字段提取。宽松<b>不等于不设防</b>：值仍要清洗控制字符，否则一个含换行的
 * 文件名就能往清单正文里注入伪造的行。
 */
public final class MediaReferencePreservingCompactionStrategy implements CompactionStrategy {

    /** 清单保留的引用条数上限——不封顶的话，长会话里这张清单自己就成了新的上下文问题。 */
    public static final int MAX_MANIFEST_ENTRIES = 20;

    /** 单个字段在清单里的最大字符数：文件名可以长到 255，一行撑爆了反而看不清。 */
    private static final int MAX_FIELD_CHARS = 80;

    public static final String MANIFEST_HEADER =
            "[会话附件清单] 以下文件/图片在更早的对话中出现过，仍可用 Read 查看：";

    /** 字段缺失时的占位——留空会让列对不齐，读的人分不清是缺值还是错位。 */
    private static final String ABSENT = "-";

    private final CompactionStrategy delegate;

    public MediaReferencePreservingCompactionStrategy(CompactionStrategy delegate) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
    }

    @Override
    public CompactionResult compact(CompactionRequest request) {
        CompactionResult r = delegate.compact(request);
        List<String> lines = harvest(r.archivedEvents());
        if (lines.isEmpty()) return r;   // 纯 no-op：没有引用被压掉就什么都不塞，别拿空清单占上下文
        List<SessionEvent> compacted = new ArrayList<>();
        compacted.add(manifestEvent(lines, r, request));   // 插在最前，紧邻摘要
        compacted.addAll(r.compactedEvents());
        // 计数原样透传：eventsRemoved() 由 archivedEvents 长度算出、tokensEstimatedSaved 是 delegate 的账，
        // 这里既没归档也没省 token，改任何一个都是编造。
        return new CompactionResult(compacted, r.archivedEvents(), r.tokensEstimatedSaved());
    }

    /** 供装配处/测试穿透装饰链。 */
    public CompactionStrategy delegate() {
        return delegate;
    }

    /**
     * 从被归档的事件里捞出全部引用，渲染成清单行；已按 {@code path} 去重、按出现顺序保留最近的
     * {@link #MAX_MANIFEST_ENTRIES} 条。返回空表表示无需插清单。
     *
     * <p>末行可能是一句「另有 N 个更早的附件已不可寻址」——它也算一行，但不占条数配额。
     */
    private List<String> harvest(List<SessionEvent> archived) {
        // path → 清单行。LinkedHashMap 保出现顺序：同一 path 出现多次只留第一次那条，
        // 后面重复的既不改内容也不该把它顶到队尾（顶了会让「保留最近」的语义变成「保留最后提及」）。
        Map<String, String> byPath = new LinkedHashMap<>();
        if (archived != null) {
            for (SessionEvent ev : archived) {
                if (ev == null) continue;
                for (String text : addressableTexts(ev.getMessage())) {
                    collectBlocks(text, byPath);
                }
            }
        }
        if (byPath.isEmpty()) return List.of();

        List<String> rows = new ArrayList<>(byPath.values());
        int dropped = rows.size() - MAX_MANIFEST_ENTRIES;
        if (dropped > 0) {
            // 保留最近的、丢弃最早的：越近的附件越可能还在被谈论，也越可能没被 GC 清掉。
            rows = new ArrayList<>(rows.subList(dropped, rows.size()));
            rows.add("  （另有 " + dropped + " 个更早的附件已不可寻址）");
        }
        return rows;
    }

    /**
     * 一条事件里<b>可寻址</b>的文本片段：只认 {@link UserMessage} 正文与
     * {@link ToolResponseMessage} 的 responseData。
     *
     * <p>跳过 {@code AssistantMessage} 的理由和 {@link VisionMaterializer} 一样：模型看得见引用格式，
     * 可能在自己的回复里照抄或臆造一段；把它复述的假引用列进清单，等于让模型给自己发一个假的寻址承诺。
     */
    private List<String> addressableTexts(Message m) {
        if (m instanceof UserMessage) {
            String t = m.getText();
            return t == null ? List.of() : List.of(t);
        }
        if (m instanceof ToolResponseMessage) {
            List<String> out = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse tr : ((ToolResponseMessage) m).getResponses()) {
                if (tr.responseData() != null) out.add(tr.responseData());
            }
            return out;
        }
        return List.of();
    }

    /** 扫出文本里的每个引用块，解析后按 path 记入 {@code byPath}（首次出现者胜）。 */
    private void collectBlocks(String text, Map<String, String> byPath) {
        if (text == null) return;
        int from = 0;
        while (true) {
            int open = text.indexOf(FileReference.OPEN, from);
            if (open < 0) return;
            int close = text.indexOf(FileReference.CLOSE, open);
            if (close < 0) return;
            int end = close + FileReference.CLOSE.length();
            Map<String, String> f = fields(text.substring(open, end));
            String path = f.get("path");
            // path 是唯一的寻址依据，缺了这行这块引用列出来也没用。
            if (path != null && !path.isBlank()) {
                byPath.putIfAbsent(path, row(f, path));
            }
            from = end;
        }
    }

    /**
     * 宽松字段提取：{@code key: value} 行照单收下，同名重复取<b>首个</b>。
     *
     * <p>这里和 {@link FileReferenceParser} 的「同名重复即整块丢弃」故意不同：那边解析结果会去读磁盘，
     * 歧义必须拒；这边只是抄一行给人和模型看，为一处歧义把整条附件从清单里抹掉，代价明显更大。
     */
    private Map<String, String> fields(String block) {
        Map<String, String> f = new HashMap<>();
        for (String line : block.split("\n")) {
            int colon = line.indexOf(": ");
            if (colon > 0) {
                f.putIfAbsent(line.substring(0, colon).trim(), line.substring(colon + 2).trim());
            }
        }
        return f;
    }

    /** 渲染一条清单行：name / mime_type / dimensions / path 四列。 */
    private String row(Map<String, String> f, String path) {
        String name = f.get("name");
        if (name == null || name.isBlank()) {
            // 没有 name 就退回 path 的末段：sha 文件名不可读，但总比一个 "-" 强。
            int slash = path.lastIndexOf('/');
            name = slash >= 0 ? path.substring(slash + 1) : path;
        }
        return "  " + pad(clean(name), 24)
                + " " + pad(clean(f.get("mime_type")), 12)
                + " " + pad(clean(f.get("dimensions")), 12)
                + " " + clean(path);
    }

    /**
     * 清洗一个字段值：控制字符换成 {@code _}，超长截断。
     *
     * <p><b>控制字符这一步是防线不是美化</b>：{@code name} 全链路未清洗过的那一路就是磁盘文件名，
     * 而 Unix 文件名可以含换行——不洗，一个叫 {@code "a\n  fake.png  image/png"} 的文件就能往清单里
     * 注入一整条伪造的附件。
     */
    private String clean(String v) {
        if (v == null || v.isBlank()) return ABSENT;
        String s = MediaArtifact.sanitizeName(v);
        return s.length() <= MAX_FIELD_CHARS ? s : s.substring(0, MAX_FIELD_CHARS - 1) + "…";
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    /**
     * 造清单事件。
     *
     * <p>sessionId 取自被归档的事件而非 {@code request.session()}：归档事件必然存在（否则不会走到这里），
     * 且它就是这条清单该归属的会话；builder 的 sessionId 默认是空串，不给会直接断言失败。
     */
    private SessionEvent manifestEvent(List<String> lines, CompactionResult r, CompactionRequest request) {
        StringBuilder b = new StringBuilder(MANIFEST_HEADER);
        for (String line : lines) b.append('\n').append(line);

        UserMessage msg = UserMessage.builder()
                .text(b.toString())
                // ★ 标记写在 Message 上：VisionMaterializer.isSynthetic 判的是消息，不是事件。
                .metadata(Map.of(VisionMaterializer.SYNTHETIC_KEY, true))
                .build();

        return SessionEvent.builder()
                .sessionId(sessionId(r, request))
                .message(msg)
                // 事件层同样标记，语义与摘要事件对齐（两者都不是用户真说过的话）。
                .metadata(Map.of(
                        SessionEvent.METADATA_SYNTHETIC, true,
                        SessionEvent.METADATA_COMPACTION_SOURCE, "media-reference-manifest",
                        VisionMaterializer.SYNTHETIC_KEY, true))
                .build();
    }

    private String sessionId(CompactionResult r, CompactionRequest request) {
        for (SessionEvent ev : r.archivedEvents()) {
            if (ev != null && ev.getSessionId() != null && !ev.getSessionId().isBlank()) {
                return ev.getSessionId();
            }
        }
        return request != null && request.session() != null ? request.session().id() : "unknown";
    }
}

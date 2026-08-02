package io.github.javaside.springai.codetui.agent.media;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把出站 {@link Prompt} 里「当轮」的图片引用兑现成真 {@link Media}。本类是纯函数式的判断中心，
 * 装饰器只负责接线。
 *
 * <p><b>当轮的定义（纯位置规则，无状态）</b>：
 * <blockquote>当轮起点 = 最后一条<b>非合成</b> {@link UserMessage} 的下标。
 * 它自己 + 它之后的消息里的引用才兑现，之前的不兑现。</blockquote>
 * 于是历史图片的上下文占用恒为零——聊 100 轮、贴过 50 张图，请求里也只有当轮那几张。
 * 模型想重看历史图就 {@code Read} 一次（Read 一张图 → 工具结果出现引用 → 落在当轮 → 被兑现），
 * 那条路本来就通，不需要新工具。
 *
 * <p><b>为什么工具图要合成一条 user 消息</b>：{@link ToolResponseMessage} 没有 media 字段
 * （只有 {@code UserMessage}/{@code AssistantMessage} 实现 {@code MediaContent}，而 assistant
 * 是模型自己的输出、语义相反且各家不收输入图）。故唯一出路是造一条 user 消息追加。
 * 该序列已真机验证：对照实验中不挂 Media 时模型明确回答「只看到图片文件引用，无法看到实际
 * 图像内容」，挂上后三种颜色全部答对——合成这一步是必需的，不是绕路。
 *
 * <p><b>工具结果那条一个字都不改</b>：引用文本是图与路径的绑定，模型读多张图时全靠它分辨哪张是哪张。
 *
 * <p><b>为什么合成消息要自带标记</b>：今天它不会回流进下一轮（字节码核实
 * {@code ToolCallingAdvisor} 从 {@code request.prompt()} 派生下一轮消息，而装饰器在整条
 * advisor 链下游）。但那是<b>库的内部行为</b>，升级即可能静默反转——届时合成消息会变成
 * 「最后一条 UserMessage」，锚点前移，之后什么都不兑现，<b>模型在回合中途突然看不见图，
 * 不报错不崩，只是答得变差</b>。故正确性改由我们自己写进消息的 {@link #SYNTHETIC_KEY} 保证。
 */
public final class VisionMaterializer {

    /** 合成消息的自证标记。锚点判定与压缩清单共用同一个键。 */
    public static final String SYNTHETIC_KEY = "codetui.synthetic";

    /** 合成消息的开头。机器口吻 + 指明来源，模型才分得清这不是用户新提的要求。 */
    private static final String SYNTHETIC_HEADER = "以下是上面工具结果中引用的图片：";

    private final Path root;
    private final ImagePreparer preparer;
    private final VisionBudget budget;

    private volatile VisionSnapshot lastSnapshot = VisionSnapshot.EMPTY;

    public VisionMaterializer(Path root, ImagePreparer preparer, VisionBudget budget) {
        this.root = root;
        this.preparer = preparer;
        this.budget = budget;
    }

    /** <b>本回合</b>累计兑现的统计（供 {@code /context}）。 */
    public VisionSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    /**
     * 把本次兑现结果并进「本回合累计」快照。
     *
     * <p><b>兑现 0 张不清零</b>——这正是这个统计此前恒为零的根因：一个回合有几十次工具迭代，
     * {@link VisionBudget#MAX_TURN_DELIVERIES} 用尽后每次都兑现 0；回合结束后引用落进历史，
     * 按「当轮兑现」规则更不会再兑现。用户按 {@code /context} 那一刻的「上一次请求」几乎必然是 0。
     * 只有 <b>turnKey 变了</b>（换回合、或换 agent）才归零重算。
     *
     * <p><b>单槽而非按 turnKey 分桶</b>（{@link VisionBudget} 是分桶的）：那边分桶是<b>正确性</b>
     * 需要——并发子 agent 共用计数器会互相冲掉额度、真的多发图；这边只是<b>显示</b>，而
     * {@code /context} 要显示的就是「用户当前这一轮」。分桶反而说不清面板该读哪个桶。
     * 代价是并发子 agent 的回合会覆盖主 agent 的账，两者交替时数字会跳。
     *
     * <p>读旧值→加→写回不是原子的，并发下最坏是丢一次累加（统计信息，可接受）；但每次写出去的
     * 都是一个完整 record，读侧绝不会看到「张数已加、token 未加」的半截状态。
     */
    private void accumulate(String turnKey, int images, long tokens) {
        VisionSnapshot prev = lastSnapshot;
        VisionSnapshot base = turnKey.equals(prev.turnKey())
                ? prev
                : new VisionSnapshot(turnKey, 0, 0L);
        lastSnapshot = base.plus(images, tokens);
    }

    /**
     * 兑现当轮引用。{@code visionCapable=false} 或全局关闭 → <b>原样返回同一个对象</b>
     * （零行为变化，且调用方可用 {@code ==} 判断有没有动过）。
     *
     * <p><b>绝不抛异常</b>：这在出站热路径上，任何失败都必须降级为「不兑现」，不能连累请求本身。
     */
    public Prompt materialize(Prompt prompt, boolean visionCapable) {
        if (!visionCapable || prompt == null) {
            return prompt;
        }
        try {
            return doMaterialize(prompt);
        } catch (RuntimeException e) {
            return prompt;   // 兑现失败绝不连累请求
        }
    }

    private Prompt doMaterialize(Prompt prompt) {
        List<Message> msgs = prompt.getInstructions();
        // 空消息列表算不出 turnKey，也不构成「新的一轮」——保留上一回合的账，别把它抹掉。
        if (msgs == null || msgs.isEmpty()) {
            return prompt;
        }

        int anchor = lastRealUserIndex(msgs);
        // turnKey 必须在一个回合的所有迭代里恒定、又要跟别的回合/别的子 agent 区分开。
        // 锚点文本 + 锚点下标恰好满足：工具循环每次迭代只在锚点<b>之后</b>追加消息，锚点本身不动。
        String turnKey = anchor < 0
                ? "no-anchor"
                : Objects.hashCode(msgs.get(anchor).getText()) + ":" + anchor;
        VisionBudget.Session session = budget.open(turnKey);

        // sha 去重表：user 先加，从而用户图天然优先——同一张图用户贴过、模型又 Read 了一次，
        // 只发一份，且留在用户那条消息上（那才是他的意图所在）。
        Set<String> seen = new HashSet<>();

        List<ParsedReference> userRefs = collectUserRefs(msgs, anchor, seen);
        List<ParsedReference> toolRefs = collectToolRefs(msgs, anchor, seen);

        // 用户图<b>先</b>过预算：预算是先到先得，顺序即优先级。反过来会让「照这张稿子改」的
        // 稿子被随后 Read 的图挤掉——功能在最典型的用法上直接失效。
        Map<ParsedReference, Outcome> userOutcomes =
                admitAll(userRefs, VisionBudget.MAX_USER_IMAGES, session);
        Map<ParsedReference, Outcome> toolOutcomes =
                admitAll(toolRefs, VisionBudget.MAX_TOOL_IMAGES, session);

        Map<ParsedReference, Media> toolMedia = deliveredMedia(toolOutcomes);

        // 短路条件是「什么都没改」而<b>不是</b>「什么都没兑现」：一张都没兑现但有引用被判超预算时，
        // 那些 delivery 行仍必须改写，否则模型拿到的是「Read 一次就能看」这句假话。
        if (userOutcomes.isEmpty() && toolMedia.isEmpty()) {
            accumulate(turnKey, 0, 0L);   // 本次兑现 0 张：同回合保持原账，换回合才归零
            return prompt;              // 原样返回同一对象，调用方可用 == 判断
        }

        List<Message> out = new ArrayList<>(msgs);
        if (!userOutcomes.isEmpty()) {
            out.set(anchor, rewriteAnchor((UserMessage) msgs.get(anchor), userOutcomes));
        }
        if (!toolMedia.isEmpty()) {
            out.add(synthesise(toolMedia));
        }

        int delivered = deliveredMedia(userOutcomes).size() + toolMedia.size();
        accumulate(turnKey, delivered, deliveredTokens(userOutcomes) + deliveredTokens(toolOutcomes));
        return prompt.mutate().messages(out).build();
    }

    /**
     * 一条引用过闸后的结局：兑现到的 {@link Media}（被跳过时为 {@code null}）+ 该写进
     * {@code delivery} 行的状态。
     *
     * <p>只记成功的不够：被预算挤掉的那些若不留结局，delivery 行就会留在 {@code not_in_view}，
     * 那句话的意思是「Read 一次就能看」——对一张会再次撞上同一个预算的图，这是假话。
     */
    private record Outcome(Media media, String delivery, long tokens) {}

    /** 从结局表里挑出真兑现了的，保持 {@link LinkedHashMap} 的顺序。 */
    private Map<ParsedReference, Media> deliveredMedia(Map<ParsedReference, Outcome> outcomes) {
        Map<ParsedReference, Media> out = new LinkedHashMap<>();
        for (Map.Entry<ParsedReference, Outcome> e : outcomes.entrySet()) {
            if (e.getValue().media() != null) out.put(e.getKey(), e.getValue().media());
        }
        return out;
    }

    /**
     * 真发出去的那几张的 token 之和。
     *
     * <p>不能用 {@code session.tokensUsed()}：那是<b>过闸尝试</b>的累计，
     * {@link VisionBudget.Session#admit} 先记账、随后可能被 {@code tryConsumeTurnSlot} 挡下。
     * 按请求报时这点误差看不出来；改成按回合累计后，回合额度用尽的那几十次迭代会次次记上一笔
     * ——张数不涨、token 一直涨，面板直接变成胡说。
     */
    private long deliveredTokens(Map<ParsedReference, Outcome> outcomes) {
        long sum = 0;
        for (Outcome o : outcomes.values()) {
            if (o.media() != null) sum += o.tokens();
        }
        return sum;
    }

    /**
     * 当轮起点：最后一条<b>非合成</b> {@link UserMessage} 的下标；没有则 -1。
     *
     * <p>{@code !isSynthetic} 这一句是防线不是修饰：我们自己追加的那条也是 UserMessage，
     * 一旦它漏回下一轮的消息列表就会夺走锚点，此后当轮什么都不兑现——不报错不崩，只是答得变差。
     */
    private int lastRealUserIndex(List<Message> msgs) {
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m instanceof UserMessage && !isSynthetic(m)) {
                return i;
            }
        }
        return -1;
    }

    /** 是否是本类合成的消息。判据只认我们自己写进去的标记，不依赖任何库内部行为。 */
    public static boolean isSynthetic(Message m) {
        Map<String, Object> meta = m == null ? null : m.getMetadata();
        return meta != null && Boolean.TRUE.equals(meta.get(SYNTHETIC_KEY));
    }

    /**
     * 锚点那条 user 消息里的<b>全部</b>引用，按出现顺序。
     *
     * <p>不在这里砍到 {@link VisionBudget#MAX_USER_IMAGES}：超配额的那几条也得留下来，
     * 才能把它们的 delivery 改写成 {@code budget_exceeded}。真正的张数闸门在
     * {@link #admitAll} 里。解析本来就是对整段文本做的，多留几条不多花钱。
     */
    private List<ParsedReference> collectUserRefs(List<Message> msgs, int anchor, Set<String> seen) {
        List<ParsedReference> out = new ArrayList<>();
        if (anchor < 0) {
            return out;                                  // 没有锚点就只处理其后的工具结果
        }
        for (ParsedReference r : FileReferenceParser.parse(msgs.get(anchor).getText(), root)) {
            if (seen.add(r.sha())) out.add(r);
        }
        return out;
    }

    /**
     * 锚点之后的工具结果里的引用，<b>从新到旧</b>取 {@link VisionBudget#MAX_TOOL_IMAGES} 张。
     *
     * <p>只认 {@link ToolResponseMessage}——{@code AssistantMessage} 必须跳过。模型看得见引用
     * 格式，可能在自己的回复里照抄；无差别扫描会把它复述的假引用当真兑现（而那段文本完全由
     * 模型的输出决定，等于把兑现的控制权交出去）。
     *
     * <p><b>为什么这里仍在收集阶段砍配额</b>（用户侧已改成收全、由 {@link #admitAll} 砍）：
     * 工具侧被跳过的引用<b>无处改写</b>——它在 {@code ToolResponseMessage} 里，那条一个字都不能动。
     * 多收几条只会在每次工具循环迭代里白白多做解析与存在性校验，换不来任何能写出去的信号。
     */
    private List<ParsedReference> collectToolRefs(List<Message> msgs, int anchor, Set<String> seen) {
        List<ParsedReference> out = new ArrayList<>();
        for (int i = msgs.size() - 1; i > anchor; i--) {
            Message m = msgs.get(i);
            if (!(m instanceof ToolResponseMessage)) continue;      // ★ assistant 在此被挡下
            List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) m).getResponses();
            for (int j = responses.size() - 1; j >= 0; j--) {       // 同一条消息内也是新的在后
                List<ParsedReference> refs =
                        FileReferenceParser.parse(responses.get(j).responseData(), root);
                for (int k = refs.size() - 1; k >= 0; k--) {
                    if (out.size() >= VisionBudget.MAX_TOOL_IMAGES) return out;
                    if (seen.add(refs.get(k).sha())) out.add(refs.get(k));
                }
            }
        }
        return out;
    }

    /**
     * 逐张过闸：张数配额 → 准备字节 → 请求 token 预算 → 回合累计额度。任一不过就跳过这张，
     * 不影响别的；<b>但结局都要记下来</b>，跳过的那些还得据此改写 delivery。
     *
     * <p>返回 {@link LinkedHashMap} 是有意的：合成消息的正文按这个顺序列名字，顺序稳定
     * 模型才对得上「第几张是哪个文件」。
     *
     * @param maxDeliveries 本来源的张数配额，按<b>真兑现</b>的张数计——发不出去的格式不该白占一个名额
     */
    private Map<ParsedReference, Outcome> admitAll(List<ParsedReference> refs, int maxDeliveries,
                                                   VisionBudget.Session session) {
        Map<ParsedReference, Outcome> out = new LinkedHashMap<>();
        int delivered = 0;
        for (ParsedReference r : refs) {
            // 张数已满：直接标注，<b>不</b>再 prepare。这是出站热路径，为几张注定发不出去的图
            // 解码缩放是白花钱。代价是超配额的那张若本身还是 HEIC，标注会略失准——两害取其轻。
            if (delivered >= maxDeliveries) {
                out.put(r, new Outcome(null, FileReference.DELIVERY_BUDGET_EXCEEDED, 0L));
                continue;
            }
            Optional<PreparedImage> prepared = preparer.prepare(r.file(), r.mimeType());
            if (prepared.isEmpty()) {
                // 发不出去的格式/过大/读失败：五态里没有精确对应的状态。硬套一个只会让语义更糊
                // ——budget_exceeded 会让模型以为「少贴几张就能看」，而这张<b>怎么试都发不出去</b>。
                // 故有意保持原样不改写。
                continue;
            }
            PreparedImage img = prepared.get();
            if (!session.admit(img.estimatedTokens())) {            // 本请求 token 预算满了
                out.put(r, new Outcome(null, FileReference.DELIVERY_BUDGET_EXCEEDED, 0L));
                continue;
            }
            if (!session.tryConsumeTurnSlot()) {                    // 本回合累计额度用尽
                out.put(r, new Outcome(null, FileReference.DELIVERY_TURN_EXHAUSTED, 0L));
                continue;
            }
            out.put(r, new Outcome(toMedia(r, img), FileReference.DELIVERY_DELIVERED,
                    img.estimatedTokens()));
            delivered++;
        }
        return out;
    }

    /**
     * data 必须是 {@code byte[]}：实测各家 ChatModel 组装请求时只认 {@code URI}/{@code String}/
     * {@code byte[]}，传别的类型会被<b>静默跳过</b>——图就这么无声丢了，不报错。
     */
    private Media toMedia(ParsedReference r, PreparedImage img) {
        return Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(img.mimeType()))
                .data(img.bytes())
                .name(r.name())
                .build();
    }

    /**
     * 用户贴的图：兑现的原地补到锚点那条消息上，并把<b>每一条有结局的</b>引用的 {@code delivery}
     * 行改写成它真实的下场。
     *
     * <p>兑现的不改，模型会同时收到「这张图你看不见」和那张图——自相矛盾的信号；被预算挤掉的
     * 不改，模型会以为「Read 一次就能看」，白空转一轮还花钱。改写只作用于<b>出站副本</b>，
     * 会话存储里那份保持原状。
     */
    private UserMessage rewriteAnchor(UserMessage anchorMsg, Map<ParsedReference, Outcome> outcomes) {
        String text = anchorMsg.getText();
        // 必须<b>从后往前</b>替换：下标是在原文本上算的，先动前面的会让后面的 start/end 全部错位。
        List<ParsedReference> byPositionDesc = new ArrayList<>(outcomes.keySet());
        byPositionDesc.sort((a, b) -> Integer.compare(b.start(), a.start()));
        for (ParsedReference r : byPositionDesc) {
            if (r.start() < 0 || r.end() > text.length() || r.start() >= r.end()) continue;
            String block = text.substring(r.start(), r.end());
            text = text.substring(0, r.start())
                    + FileReference.withDelivery(block, outcomes.get(r).delivery())
                    + text.substring(r.end());
        }
        UserMessage.Builder b = anchorMsg.mutate().text(text);
        // 一张都没兑现时不碰 media：mutate() 会带上原有的 media，覆写成空等于把别处挂的图擦掉。
        List<Media> media = new ArrayList<>(deliveredMedia(outcomes).values());
        if (!media.isEmpty()) {
            b.media(media);
        }
        return b.build();
    }

    /**
     * 工具产的图：只能靠合成一条 user 消息投递（{@code ToolResponseMessage} 没有 media 字段）。
     *
     * <p><b>这里只列真兑现的那几张</b>，被预算跳过的一个字都不提：正文的行序就是模型区分
     * 「第几张是哪个文件」的唯一依据，混进没带图的名字会直接把这个绑定弄错。而且合成消息在
     * 一张都没兑现时根本不存在，「没提到就是没被跳过」这个反推本来就不成立——与其给一半场景
     * 发个不可靠的信号，不如不发。
     */
    private UserMessage synthesise(Map<ParsedReference, Media> media) {
        StringBuilder b = new StringBuilder(SYNTHETIC_HEADER);
        for (ParsedReference r : media.keySet()) {
            // r.name() 已在 parser 侧清洗过控制字符——否则含换行的文件名会往这里注入伪造的行。
            b.append('\n').append("- ").append(r.name());
        }
        return UserMessage.builder()
                .text(b.toString())
                .media(new ArrayList<>(media.values()))
                .metadata(Map.of(SYNTHETIC_KEY, true))
                .build();
    }
}

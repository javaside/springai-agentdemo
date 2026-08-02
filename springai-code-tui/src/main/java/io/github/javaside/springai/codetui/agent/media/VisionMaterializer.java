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

    /** 上次兑现的统计（供 {@code /context}）。 */
    public VisionSnapshot lastSnapshot() {
        return lastSnapshot;
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
        if (msgs == null || msgs.isEmpty()) {
            lastSnapshot = VisionSnapshot.EMPTY;
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
        Map<ParsedReference, Media> userMedia = admitAll(userRefs, session);
        Map<ParsedReference, Media> toolMedia = admitAll(toolRefs, session);

        if (userMedia.isEmpty() && toolMedia.isEmpty()) {
            lastSnapshot = VisionSnapshot.EMPTY;
            return prompt;              // 什么都没兑现 → 原样返回同一对象，调用方可用 == 判断
        }

        List<Message> out = new ArrayList<>(msgs);
        if (!userMedia.isEmpty()) {
            out.set(anchor, attachToAnchor((UserMessage) msgs.get(anchor), userMedia));
        }
        if (!toolMedia.isEmpty()) {
            out.add(synthesise(toolMedia));
        }

        lastSnapshot = new VisionSnapshot(userMedia.size() + toolMedia.size(), session.tokensUsed());
        return prompt.mutate().messages(out).build();
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

    /** 锚点那条 user 消息里的引用，按出现顺序取前 {@link VisionBudget#MAX_USER_IMAGES} 张。 */
    private List<ParsedReference> collectUserRefs(List<Message> msgs, int anchor, Set<String> seen) {
        List<ParsedReference> out = new ArrayList<>();
        if (anchor < 0) {
            return out;                                  // 没有锚点就只处理其后的工具结果
        }
        for (ParsedReference r : FileReferenceParser.parse(msgs.get(anchor).getText(), root)) {
            if (out.size() >= VisionBudget.MAX_USER_IMAGES) break;
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
     * 逐张过闸：准备字节 → 请求 token 预算 → 回合累计额度。任一不过就跳过这张，不影响别的。
     *
     * <p>返回 {@link LinkedHashMap} 是有意的：合成消息的正文按这个顺序列名字，顺序稳定
     * 模型才对得上「第几张是哪个文件」。
     */
    private Map<ParsedReference, Media> admitAll(List<ParsedReference> refs,
                                                 VisionBudget.Session session) {
        Map<ParsedReference, Media> out = new LinkedHashMap<>();
        for (ParsedReference r : refs) {
            Optional<PreparedImage> prepared = preparer.prepare(r.file(), r.mimeType());
            if (prepared.isEmpty()) continue;                       // 发不出去的格式/过大/读失败
            PreparedImage img = prepared.get();
            if (!session.admit(img.estimatedTokens())) continue;    // 本请求 token 预算满了
            if (!session.tryConsumeTurnSlot()) continue;            // 本回合累计额度用尽
            out.put(r, toMedia(r, img));
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
     * 用户贴的图：原地补到锚点那条消息上，并把它自己的 {@code delivery} 行改成 delivered。
     *
     * <p>不改 delivery 的话，模型会同时收到「这张图你看不见」和那张图——自相矛盾的信号。
     * 改写只作用于<b>出站副本</b>，会话存储里那份保持原状。
     */
    private UserMessage attachToAnchor(UserMessage anchorMsg, Map<ParsedReference, Media> media) {
        String text = anchorMsg.getText();
        // 必须<b>从后往前</b>替换：下标是在原文本上算的，先动前面的会让后面的 start/end 全部错位。
        List<ParsedReference> byPositionDesc = new ArrayList<>(media.keySet());
        byPositionDesc.sort((a, b) -> Integer.compare(b.start(), a.start()));
        for (ParsedReference r : byPositionDesc) {
            if (r.start() < 0 || r.end() > text.length() || r.start() >= r.end()) continue;
            String block = text.substring(r.start(), r.end());
            text = text.substring(0, r.start())
                    + FileReference.withDelivery(block, FileReference.DELIVERY_DELIVERED)
                    + text.substring(r.end());
        }
        return anchorMsg.mutate()
                .text(text)
                .media(new ArrayList<>(media.values()))
                .build();
    }

    /** 工具产的图：只能靠合成一条 user 消息投递（{@code ToolResponseMessage} 没有 media 字段）。 */
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

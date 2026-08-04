package io.github.javaside.springai.codetui.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * 「上次用的模型」的落盘：{@code <root>/.codetui/model.json}，单键 {@code lastModel}。
 *
 * <p><b>键名是 lastModel 而不是 model</b>：在名字上说清这是「上次用的」，不是「你配置的默认」。
 * 将来真要加显式配置项，两者可以共存而不打架。
 *
 * <p><b>只记 modelId，不记 provider</b>：{@code ProviderRegistry.select(String)} 的既有语义
 * 就是「在可用 provider 里找拥有该 id 的那家」。{@code *_MODELS} 环境变量可能造成跨家重名，
 * 此时命中列表序靠前的可用家——但 {@code /model} 面板本身也只能按 id 选，UI 层面同样区分不了重名。
 * 只记 id 与现有交互完全一致，不引入新的不一致。这是已知限制，不是疏忽。
 *
 * <p><b>降级契约</b>：读侧任何情况都返回 {@link Optional}、写侧任何情况都返回 boolean，
 * <b>两边都绝不抛</b>。这个类跑在启动路径上，抛一次异常就是 code-tui 起不来。
 *
 * <h2>为什么整份覆盖，而不是照 permissions.json 那样读-改-写</h2>
 * <p>{@code permissions.json} 里有用户手写的规则和未知字段，必须原样保留，所以那边走
 * Jackson 树模型读-改-写。本文件是<b>纯机器写的单键文件</b>，没有用户内容要保护，整份覆盖
 * 更简单也更不容易写坏。
 *
 * <p><b>绊线</b>：这个文件哪天长出第二个键，写侧就<b>必须</b>改回读-改-写，
 * 否则整份覆盖会悄悄吃掉另一个键。
 *
 * <h2>并发</h2>
 * <p>进程内不加锁：写只发生在 UI 线程的 {@code /model} 选中分支。
 * （{@code PermissionConfigWriter} 那把静态锁是因为工具并行审批会同时回写，这里没有对应场景。）
 * 跨进程（同一项目开两个窗口）是 last-writer-wins：单键文件，最坏结果是「记住了另一个窗口选的模型」；
 * 临时文件名带随机后缀，不会互相写坏。
 */
public final class ModelPreference {

    private static final Logger log = LoggerFactory.getLogger(ModelPreference.class);

    private static final String KEY = "lastModel";

    /**
     * 开重复键检测。
     *
     * <p><b>这里的理由与 {@code PermissionConfigWriter} 不同</b>：那边开它是因为写侧读-改-写
     * 时「末键胜出」会不可逆地抹掉用户的 deny 规则；本文件整份覆盖、写前不读旧值，那条理由
     * 不成立。开它只是因为——这是台<b>纯机器写</b>的文件，里面出现重复键只可能是人手改坏了或
     * 别的程序写脏了，此时宁可当无记忆处理，也不要在两个值里猜一个。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private ModelPreference() {
    }

    /** 偏好文件路径。暴露出来是为了让测试能直接摆一个坏文件进去。 */
    public static Path fileFor(Path root) {
        return root.resolve(".codetui").resolve("model.json");
    }

    /**
     * 读上次用的模型 id。缺失/坏文件/空值一律 {@link Optional#empty()}，绝不抛。
     *
     * <h2>往 {@link #doRead} 加守卫的人请读这段</h2>
     * <p>下面那个 catch-all 有代价：它会把<b>会抛的</b>显式守卫的变异检测力吃掉。
     * 守卫删掉后异常被它吸收成 {@code empty}，与正确路径输出<b>一模一样</b>，只断返回值的测试杀不掉——
     * 那条守卫等于没有保护。故新加<b>会抛的</b>守卫时，必须连带加一条<b>「这条路不打日志」</b>的断言
     * （照 {@code ModelPreferenceTest} 里那三条的写法）。
     *
     * <p><b>只有会抛的守卫受此限。</b>守卫没了只是返回错值的（如 {@code id.isEmpty()} 会返回
     * {@code Optional.of("")}），返回值断言照样杀得掉，不用加。
     *
     * <p>当前会抛的守卫共三条，均已钉死：{@code root == null}、{@code v == null}、{@code !v.isString()}。
     */
    public static Optional<String> read(Path root) {
        try {
            return doRead(root);
        } catch (Exception e) {
            // 「绝不抛」是硬契约：这个类跑在启动路径上，漏一个异常就是 code-tui 起不来。
            // 上面每条已知路径都各自降级了，能到这里的只可能是「将来改 doRead 的人漏判的那个」。
            //
            // 这里带上整个异常（末尾多传一个 e）而不是像别处那样只取 getMessage()：
            // 别处兜的是已知路径，消息就够了；这里兜的是「不知道是什么」，
            // 没有栈就等于只知道出事了、不知道出在哪，这道保险也就白加了。
            log.warn("读模型偏好时出了意料之外的错（{}），本次按无记忆处理。", e.toString(), e);
            return Optional.empty();
        }
    }

    private static Optional<String> doRead(Path root) {
        if (root == null) {
            return Optional.empty();
        }
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();      // 首次运行是常态，不是错误——刻意不打日志
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (Exception e) {
            // 用 toString() 而不是 getMessage()：NoSuchFileException 之流的 getMessage()
            // 返回的就是路径本身，只取消息会把路径打两遍，括号里本该说的是「出了什么事」
            log.warn("读不出模型偏好 {}（{}），本次按无记忆处理。", file, e.toString());
            return Optional.empty();
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (Exception e) {
            log.warn("模型偏好 {} 不是合法 JSON（{}），本次按无记忆处理。", file, e.getMessage());
            return Optional.empty();
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        JsonNode v = node.get(KEY);
        if (v == null || !v.isString()) {     // Jackson 3：非文本节点调 stringValue() 会抛
            return Optional.empty();
        }
        String id = v.stringValue().trim();
        return id.isEmpty() ? Optional.empty() : Optional.of(id);
    }

    /**
     * 记住这个模型 id。失败返回 false 且<b>不改动原文件</b>，绝不抛。
     *
     * <p>原子写：先写同目录临时文件（随机后缀，两个进程同时写不会互相写坏），
     * 再 {@code ATOMIC_MOVE}；不支持的文件系统降级普通替换。
     */
    public static boolean write(Path root, String modelId) {
        if (root == null || modelId == null || modelId.isBlank()) {
            return false;
        }
        Path file = fileFor(root);
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());     // 全新项目还没有 .codetui/
            ObjectNode node = MAPPER.createObjectNode();
            node.put(KEY, modelId);
            tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            cleanup(tmp);
            log.warn("模型偏好没能落盘 {}（{}），仅本次运行生效。", file, e.getMessage());
            return false;
        }
    }

    private static void cleanup(Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // 清不掉就算了：留一个 .tmp 远比在失败路径上再抛一次好
        }
    }
}

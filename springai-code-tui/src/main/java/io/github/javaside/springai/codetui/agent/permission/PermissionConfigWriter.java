package io.github.javaside.springai.codetui.agent.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 「允许，永久」的落盘：往 {@code permissions.json} 的 allow/ask/deny 数组追加一条规则，
 * 其余树原样保留（Jackson 树模型读-改-写，未知字段与条目顺序不变）。
 *
 * <p>原子写：先写同目录临时文件再 move（优先 {@code ATOMIC_MOVE}，不支持的文件系统降级普通替换），
 * 防写一半损坏配置（照 {@code McpConfigWriter} / {@code FileSessionRepository} 的既有范式）。
 *
 * <p><b>降级契约</b>：JSON 非法 / 写失败 → 记 WARN、返回 false、<b>不改动原文件、绝不抛异常</b>。
 * 调用方（{@link PermissionEngine}）据 false 让 UI 提示「仅本次运行生效」。
 *
 * <p><b>幂等</b>：同一条 DSL 已存在则直接返回 true，不重复追加。
 *
 * <h2>为什么写之前要先把 DSL 解析回来比一遍</h2>
 * <p>这个文件是<b>授权</b>本身：写进去的字符串下一次启动会被
 * {@link PermissionRule#parse} 当规则重新读出来。而 {@code toDsl()} 只是字符串拼接，
 * 不保证读回来还是同一条规则——规则未必来自 {@code parse}，上游 {@code suggest()} 是直接构造记录的。
 * 两种走样都让落盘的授权<b>宽于</b>用户批准的那一次：
 * <ul>
 *   <li>模式带前导空格：{@code Bash( rm:*)} 被 {@code parse} trim 成 {@code Bash(rm:*)}；</li>
 *   <li>工具名里含 {@code (}：{@code Ba(sh(x)} 被读成工具 {@code Ba} 的规则，
 *       字符串却原样往返，只比字符串是发现不了的。</li>
 * </ul>
 * 故写前把 DSL 解析回来<b>逐字段</b>比对，不一致就拒绝落盘、由调用方退回会话级规则。
 *
 * <h2>并发</h2>
 * <p>agent 会并行跑工具，两次审批可能同时回写同一文件。读-改-写不加锁会丢更新
 * （后写的树是基于旧快照的，前一条规则消失，而 {@code append} 却返回了 true——
 * 用户被告知「永久」，实际没落盘）。故进程内用一把静态锁串行化。
 * <b>跨进程</b>（同一项目开两个 code-tui）仍可能丢更新：临时文件名带随机后缀，
 * 因此不会互相写坏，最坏情形是丢一条规则——比写出半个 JSON 可接受得多。
 */
public final class PermissionConfigWriter {

    private static final Logger log = LoggerFactory.getLogger(PermissionConfigWriter.class);

    /**
     * 必须与 {@link PermissionConfigLoader} 用同一套解析开关——重复键检测尤其不能只开在读侧。
     *
     * <p>只开读侧会留下一条比原问题更糟的路：读侧把重复键文件判非法（规则全丢），
     * 写侧却用 Jackson 默认的<b>末键胜出</b>把它读成一棵「正常」的树，追加一条规则后
     * <b>原样重写回磁盘</b>——那条被末键覆盖掉的 deny 就此从文件里<b>真正消失</b>，
     * 且 {@code append} 返回 true，用户被告知「永久允许」成功，毫不知情。
     * 读侧的拒绝是临时的（改回文件即恢复），写侧这一下是不可逆的。
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** 进程内串行化读-改-写，防并行审批丢更新（见类注释「并发」）。 */
    private static final Object LOCK = new Object();

    private PermissionConfigWriter() {
    }

    public static boolean append(Path file, PermissionRule rule) {
        if (file == null || rule == null) {
            return false;
        }
        String dsl;
        try {
            dsl = rule.toDsl();
        } catch (RuntimeException e) {
            log.warn("权限配置回写失败：规则无法还原成 DSL（{}）。", e.getMessage());
            return false;
        }
        if (!roundTrips(dsl, rule)) {
            log.warn("权限配置回写失败：规则 '{}' 重新解析后不是同一条，拒绝落盘（见类注释）。", dsl);
            return false;
        }
        synchronized (LOCK) {
            return doAppend(file, dsl, rule.behavior());
        }
    }

    private static boolean doAppend(Path file, String dsl, PermissionBehavior behavior) {
        Path tmp = null;
        try {
            ObjectNode root = readRoot(file);
            if (root == null) {
                return false;                     // 已在 readRoot 记 WARN；原文件一个字节都不动
            }

            String key = arrayKey(behavior);
            JsonNode arr = root.get(key);
            if (arr != null && !arr.isArray()) {
                // 手写坏了的字段。putArray 会把它顶掉——那是静默丢用户写的内容，
                // 与「JSON 非法就整个不动」同一个道理，这里也宁可不写。
                log.warn("权限配置回写失败：{} 的 \"{}\" 字段不是数组，请先修正该文件。", file, key);
                return false;
            }
            ArrayNode array = arr != null ? (ArrayNode) arr : root.putArray(key);

            for (JsonNode n : array) {
                // Jackson 3 的 stringValue() 对非文本节点会抛，必须先判 isString
                if (n.isString() && dsl.equals(n.stringValue())) {
                    return true;      // 幂等：已存在
                }
            }
            array.add(dsl);

            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);  // 全新项目还没有 .codetui/
            }
            // 临时文件名带随机后缀：两个进程同时回写时不会写进同一个 tmp 再各自 move 出半个 JSON
            tmp = file.resolveSibling(file.getFileName() + "." + UUID.randomUUID() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            log.warn("权限配置回写失败：{} 规则 '{}'：{}", file, dsl, e.getMessage());
            cleanup(tmp);
            return false;
        }
    }

    /**
     * 读出待改的树。文件不存在或内容空白 → 新建空对象；
     * JSON 非法或顶层不是对象 → 记 WARN 返回 {@code null}（<b>不覆盖</b>）。
     *
     * <p>非法就不覆盖是刻意的：{@link PermissionConfigLoader} 对非法文件的契约是「当空处理」，
     * 若这里照样重写一遍，用户手写的规则就在他毫不知情的情况下被这一次「允许，永久」抹掉了。
     */
    private static ObjectNode readRoot(Path file) {
        if (!Files.isRegularFile(file)) {
            return MAPPER.createObjectNode();
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (Exception e) {
            log.warn("权限配置回写失败：读不出 {}（{}）。", file, e.getMessage());
            return null;
        }
        if (text.isBlank()) {
            return MAPPER.createObjectNode();     // 空文件没有可丢的内容
        }
        JsonNode existing;
        try {
            existing = MAPPER.readTree(text);
        } catch (Exception e) {
            log.warn("权限配置回写失败：{} 不是合法 JSON（{}），保持原文件不动。", file, e.getMessage());
            return null;
        }
        if (existing == null || !existing.isObject()) {
            log.warn("权限配置回写失败：{} 顶层不是 JSON 对象。", file);
            return null;
        }
        return (ObjectNode) existing;
    }

    /**
     * 落盘的 DSL 读回来是否还是同一条规则：字符串往返 <b>且</b> 工具名/模式逐字段相等（见类注释）。
     */
    private static boolean roundTrips(String dsl, PermissionRule rule) {
        PermissionRule parsed = PermissionRule.parse(dsl, rule.behavior(), rule.scope());
        return parsed != null
                && dsl.equals(parsed.toDsl())
                && parsed.toolName().equals(rule.toolName())
                && java.util.Objects.equals(parsed.pattern(), rule.pattern());
    }

    private static void cleanup(Path tmp) {
        if (tmp == null) {
            return;
        }
        try {
            Files.deleteIfExists(tmp);
        } catch (Exception ignored) {
            // 清不掉就算了，留个临时文件远比为此抛异常好
        }
    }

    private static String arrayKey(PermissionBehavior b) {
        return switch (b) {
            case ALLOW -> "allow";
            case ASK -> "ask";
            case DENY -> "deny";
        };
    }
}

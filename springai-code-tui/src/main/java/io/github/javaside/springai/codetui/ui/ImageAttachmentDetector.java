package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.ImageDimensions;
import io.github.javaside.springai.codetui.agent.media.MagicSniffer;
import io.github.javaside.springai.codetui.agent.media.MediaKind;
import io.github.javaside.springai.codetui.agent.media.VisionBudget;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从输入框文本里识别待附的图片。识别的<b>全部</b>复杂度在这里，UI 层只剩渲染与按键。
 *
 * <p><b>为什么不引入 {@code @} 标记</b>：用户明确选了裸路径自动识别（拖拽白送支持——终端
 * 不传文件、只把路径当粘贴插进来）。代价是无法用语法表达「这个路径不要当图片」，
 * {@code 把 docs/bug.png 复制到 tmp/} 必然误附。该代价由<b>附件行可见 + Ctrl+X 撤销</b>承担，
 * <b>不靠规则消灭</b>——任何试图从句意猜意图的规则都比误附本身更不可预测。
 *
 * <p><b>刻意不做 root 包含校验</b>：那道校验（{@code PathContainment}）是防外部注入的，
 * 属于<b>引用块解析</b>阶段。用户自己拖进来的文件意图明确，在这里拦会让「从桌面拖图」失效。
 * 越界的图由调用方复制进 artifacts，写进引用块的 path 因此落回 root 内。
 *
 * <p><b>缓存</b>：边打边识别意味着每次击键都要重新扫一遍文本。按「路径 + mtime」缓存，
 * 否则每个字符都要读盘做魔数嗅探。
 */
public final class ImageAttachmentDetector {

    /** 识别结果 + 因超上限被丢弃的张数（附件行要如实说出来，不能静默截断）。 */
    public record Result(List<DetectedImage> images, int overflow) {
        static final Result EMPTY = new Result(List.of(), 0);
    }

    /**
     * 嗅探只读文件头：跑在每次击键上，若 readAllBytes 一个几百 MB 的视频/大图，首次命中就会
     * 卡死渲染线程。魔数与 PNG/JPEG 尺寸都在头部，64 KiB 足够（Tika 的 magic 只看前几 KB）。
     * 代价是 SOF 段落在 64 KiB 之外的巨幅 JPEG 取不到宽高——退化成 0/0，record 本就允许。
     */
    private static final int HEAD_BYTES = 64 * 1024;

    /** 缓存条目：算出这份结果时文件的 mtime。空 result 表示「不是可附的图片」（负结果同样缓存）。 */
    private record Cached(long mtime, Optional<DetectedImage> result) {}

    /** 缓存：绝对路径 → 上次嗅探结果。mtime 变了就重算，故文件被改写/替换不会用陈旧结果。 */
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 识别（截断到上限）。 */
    public List<DetectedImage> detect(String text, Path root) {
        return detectWithOverflow(text, root).images();
    }

    /** 识别，并报告被上限丢弃了几张。 */
    public Result detectWithOverflow(String text, Path root) {
        if (text == null || text.isBlank() || root == null) return Result.EMPTY;
        List<DetectedImage> all = new ArrayList<>();
        for (String token : tokenize(text)) {
            resolve(token, root).ifPresent(all::add);
        }
        if (all.size() <= VisionBudget.MAX_USER_IMAGES) {
            return new Result(List.copyOf(all), 0);
        }
        return new Result(List.copyOf(all.subList(0, VisionBudget.MAX_USER_IMAGES)),
                all.size() - VisionBudget.MAX_USER_IMAGES);
    }

    /**
     * 按空白切词，但尊重三种<b>终端拖拽会产生</b>的转义形态：反斜杠转义空格
     * （macOS Terminal.app / iTerm2 默认，中文截图文件名就带空格，漏了这条
     * 「从桌面拖图」完全失效）、单引号包裹、双引号包裹。
     * 返回的是<b>已去转义</b>的词。
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {                       // 反斜杠后的字符原样收，不参与切词
                cur.append(c);
                escaped = false;
            } else if (c == '\\' && quote == 0) {
                // 引号内不处理反斜杠转义：POSIX 下 '\' 是合法文件名字符，而 Windows 路径
                // （"C:\shots\a.png"，第三种拖拽形态）整条由反斜杠分隔——在引号内当转义符
                // 会把它吃成 "C:shotsa.png"。引号已经界定了词边界，无需再靠转义。
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 一个词能否解析成可附的图片。任何一条判据不过 → 空（当普通文本，不报错不提示）。 */
    private Optional<DetectedImage> resolve(String token, Path root) {
        Path file = toPath(token, root);
        if (file == null) return Optional.empty();
        String key;
        long mtime;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return Optional.empty();
            key = file.toAbsolutePath().normalize().toString();
            mtime = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return Optional.empty();
        }
        Cached hit = cache.get(key);
        if (hit != null && hit.mtime() == mtime) return hit.result();   // 命中：不读盘、不嗅探
        Optional<DetectedImage> fresh = sniff(file, root);
        cache.put(key, new Cached(mtime, fresh));
        return fresh;
    }

    /** 词 → 路径。{@code ~} 展开；相对路径以 <b>project root</b> 为基准（不是进程 CWD）。 */
    private static Path toPath(String token, Path root) {
        if (token.isEmpty() || token.length() > 4096) return null;   // 4096≈PATH_MAX，防病态输入
        try {
            if (token.equals("~")) return null;                       // 家目录本身不是文件
            if (token.startsWith("~/")) {
                return Path.of(System.getProperty("user.home")).resolve(token.substring(2));
            }
            Path p = Path.of(token);
            return p.isAbsolute() ? p : root.resolve(p);
        } catch (Exception e) {                                        // 非法路径字符（NUL 等）
            return null;
        }
    }

    /** 读文件头判魔数 + 取尺寸。<b>绝不抛异常</b>——这跑在每次击键上。 */
    private static Optional<DetectedImage> sniff(Path file, Path root) {
        try {
            byte[] bytes = readHead(file);
            if (MagicSniffer.sniff(bytes).kind() != MediaKind.IMAGE) {
                return Optional.empty();
            }
            Optional<int[]> dim = ImageDimensions.of(bytes);
            boolean inside = file.toAbsolutePath().normalize()
                    .startsWith(root.toAbsolutePath().normalize());
            return Optional.of(new DetectedImage(file, file.getFileName().toString(),
                    dim.map(d -> d[0]).orElse(0), dim.map(d -> d[1]).orElse(0), inside));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 读前 {@link #HEAD_BYTES} 字节（文件更短就读多少算多少）。 */
    private static byte[] readHead(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(HEAD_BYTES);
        }
    }
}

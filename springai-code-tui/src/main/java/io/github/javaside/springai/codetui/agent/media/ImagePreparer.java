package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 把磁盘图片准备成「可以真发出去的字节」：必要时缩放、转码，并挡掉发不出去的格式。
 *
 * <p><b>为什么需要一张决策表</b>（实测 JDK 21）：ImageIO 支持集与各家 API 接受集<b>不重合</b>。
 * ImageIO 只认 bmp/gif/jpeg/png/tiff/wbmp，不认 WebP/HEIC/AVIF；而各家 API 收 png/jpeg/gif/webp。
 * <pre>
 *   PNG/JPEG/GIF   ImageIO✅ API✅  → 长边 &gt;1568 则缩
 *   BMP/TIFF       ImageIO✅ API❌  → 解码后转码为 PNG
 *   WebP           ImageIO❌ API✅  → 原样发，不缩
 *   HEIC/AVIF      ImageIO❌ API❌  → 不兑现（HEIC 是 iPhone 照片默认格式，会真遇到）
 * </pre>
 *
 * <p><b>OOM 防护</b>：先用 header-only 读尺寸（{@code ImageReader.getWidth(0)} 无需整图解码），
 * 超过 {@link #MAX_PIXELS} 直接拒绝。绝不「先解码再判断」——200MB 的 PNG 解成
 * {@code BufferedImage} 是 GB 级，那一下就把进程带走了。同理，判定期间也绝不把整个文件读进
 * {@code byte[]}：嗅探只喂文件头，原始字节只在确认要原样发出时才读。
 *
 * <p><b>缓存不是优化而是必需</b>：一个回合的工具循环跑 6 次迭代、每次重新组装请求，
 * 不缓存就要把同一张图解码编码 6 遍。按「路径 + mtime + 目标边长」做键。
 */
public final class ImagePreparer {

    private static final Logger log = LoggerFactory.getLogger(ImagePreparer.class);

    /** 长边上限（Anthropic 建议值）。4K 截图缩到这个尺寸对「看报错」零损失。 */
    public static final int MAX_EDGE = 1568;
    /** 像素上限：超过即拒绝，防解码 OOM。 */
    public static final long MAX_PIXELS = 50L * 1000 * 1000;
    /** 单图字节上限（Anthropic 约 5MB，取保守值）。 */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    /** 嗅探只需文件头——魔数都在开头几十字节，读整个文件只会把 OOM 风险原样搬到判定阶段。 */
    private static final int SNIFF_HEAD_BYTES = 64 * 1024;

    private static final Set<String> SCALABLE = Set.of("image/png", "image/jpeg", "image/gif");
    private static final Set<String> TRANSCODE_TO_PNG =
            Set.of("image/bmp", "image/tiff", "image/x-ms-bmp");
    private static final Set<String> PASS_THROUGH = Set.of("image/webp");

    private final Map<String, PreparedImage> cache = new ConcurrentHashMap<>();

    /** 整图解码次数。存在的唯一理由是让「超限图必须在解码<b>之前</b>被拒」这条不变式可断言——
     *  只看返回值区分不了「拒在解码前」和「解码完再拒」，而两者的差别正是 OOM 与不 OOM。 */
    private final AtomicInteger decodeCount = new AtomicInteger();

    /** @see #decodeCount */
    int decodeCount() {
        return decodeCount.get();
    }

    /** 准备一张图；无法发出（格式不支持/过大/读失败）→ 空。<b>绝不抛异常</b>（这在出站热路径上）。 */
    public Optional<PreparedImage> prepare(Path file, String declaredMime) {
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            String key = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis()
                    + "|" + MAX_EDGE;
            PreparedImage hit = cache.get(key);
            if (hit != null) return Optional.of(hit);

            Optional<PreparedImage> made = make(file, mimeOf(file, declaredMime));
            made.ifPresent(v -> cache.put(key, v));
            return made;
        } catch (Exception e) {
            log.warn("图片准备失败 {}：{}", file.getFileName(), e.toString());   // 不打印内容
            return Optional.empty();
        }
    }

    /** 类型以磁盘魔数为准（声明值是外部输入，不可信）；嗅探不出才回退声明值。 */
    private String mimeOf(Path file, String declaredMime) {
        try {
            MagicSniffer.Sniffed s = MagicSniffer.sniff(readHead(file, SNIFF_HEAD_BYTES));
            if (s.mimeType() != null && s.mimeType().startsWith("image/")) return s.mimeType();
        } catch (Exception ignored) {
            // 读失败时退回声明值，由决策表决定认不认
        }
        return declaredMime == null ? "application/octet-stream" : declaredMime;
    }

    private Optional<PreparedImage> make(Path file, String mime) throws Exception {
        if (PASS_THROUGH.contains(mime)) {
            // 缩不了的格式，字节上限只能靠文件大小判——先看 size 再读，别把超限文件先搬进内存。
            if (Files.size(file) > MAX_BYTES) return Optional.empty();
            byte[] raw = Files.readAllBytes(file);
            int[] wh = headerSize(file).orElse(new int[]{MAX_EDGE, MAX_EDGE});
            return Optional.of(new PreparedImage(raw, mime, wh[0], wh[1], tokensOf(wh[0], wh[1])));
        }
        boolean scalable = SCALABLE.contains(mime);
        boolean transcode = TRANSCODE_TO_PNG.contains(mime);
        if (!scalable && !transcode) return Optional.empty();       // HEIC/AVIF 等：发不出去

        int[] wh = headerSize(file).orElse(null);
        if (wh == null) return Optional.empty();
        if ((long) wh[0] * wh[1] > MAX_PIXELS) {                    // OOM 防护：解码前就拒
            log.warn("图片过大不兑现 {}：{}x{}", file.getFileName(), wh[0], wh[1]);
            return Optional.empty();
        }

        // 原件字节只在「原样发」这条路上才需要；要缩/要转码时 ImageIO 直接读文件，
        // 提前 readAllBytes 只是白白多占一份大数组。
        boolean needScale = Math.max(wh[0], wh[1]) > MAX_EDGE;
        if (!needScale && !transcode && Files.size(file) <= MAX_BYTES) {
            byte[] raw = Files.readAllBytes(file);
            return Optional.of(new PreparedImage(raw, mime, wh[0], wh[1], tokensOf(wh[0], wh[1])));
        }

        decodeCount.incrementAndGet();
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) return Optional.empty();
        int[] target = fit(src.getWidth(), src.getHeight(), MAX_EDGE);
        byte[] out = encode(scale(src, target[0], target[1]), "png");
        if (out.length > MAX_BYTES) {                                // 仍超：转 JPEG 压一次
            out = encode(scale(src, target[0], target[1]), "jpg");
            if (out.length > MAX_BYTES) return Optional.empty();
            return Optional.of(new PreparedImage(out, "image/jpeg", target[0], target[1],
                    tokensOf(target[0], target[1])));
        }
        return Optional.of(new PreparedImage(out, "image/png", target[0], target[1],
                tokensOf(target[0], target[1])));
    }

    /** 只读文件头拿尺寸，<b>不解码整图</b>。这是 OOM 防护的关键一步。 */
    private Optional<int[]> headerSize(Path file) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return Optional.empty();
            ImageReader r = it.next();
            try {
                r.setInput(in);
                return Optional.of(new int[]{r.getWidth(0), r.getHeight(0)});
            } finally {
                r.dispose();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 读文件头若干字节（不足则返回实际长度），供魔数嗅探用。 */
    private static byte[] readHead(Path file, int n) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(n);
        }
    }

    /** 等比缩到长边不超过 max；本来就不超则原样返回。 */
    static int[] fit(int w, int h, int max) {
        int longEdge = Math.max(w, h);
        if (longEdge <= max) return new int[]{w, h};
        double k = (double) max / longEdge;
        return new int[]{Math.max(1, (int) Math.round(w * k)), Math.max(1, (int) Math.round(h * k))};
    }

    private BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] encode(BufferedImage img, String fmt) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ImageIO.write(img, fmt, bo);
        return bo.toByteArray();
    }

    /** 视觉 token 估算（Anthropic 口径 宽×高/750）。只用于预算，不求各家精确。 */
    static long tokensOf(int w, int h) {
        return Math.max(1L, (long) w * h / 750L);
    }
}

package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** 内容寻址存储：把字节落到 <artifactsDir>/<完整sha>.<ext>。幂等去重、原子写、惰性建目录。
 *  一切 IO 失败走 slf4j（日志绝不含字节内容），失败时抛 RuntimeException 由上层降级为占位（绝不退回原始字节）。 */
public final class MediaArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(MediaArtifactStore.class);

    private final Path artifactsDir;
    private final Path root;

    public MediaArtifactStore(Path artifactsDir, Path root) {
        this.artifactsDir = artifactsDir;
        this.root = root;
    }

    /** 存字节 → 产物（source=MATERIALIZED, ownedByStore=true）。
     *  {@code originalName} 是给模型看的可读名字：MCP 内联字节无文件名，调用方须合成一个，
     *  否则模型跨回合只能看到一串 sha，无法指认「哪一张」。 */
    public MediaArtifact put(byte[] bytes, String declaredMimeType, String originalName) {
        MagicSniffer.Sniffed sniffed = MagicSniffer.sniff(bytes);
        String sha = sha256Hex(bytes);
        String fileName = sha + "." + sniffed.ext();
        Path target = artifactsDir.resolve(fileName);
        try {
            Files.createDirectories(artifactsDir);
            if (!Files.exists(target)) {
                writeAtomic(target, bytes);
            }
        } catch (IOException e) {
            log.warn("写 artifact 失败 {}：{}", fileName, e.toString());   // 不打印 bytes
            throw new IllegalStateException("artifact 写入失败", e);
        }
        // 内容寻址换来同图去重，代价是文件名是 64 位 sha——用户想自己看一眼得复制一长串哈希。
        // 只对图片建链：视频/二进制建了也不会有人去 open。
        if (sniffed.kind() == MediaKind.IMAGE) {
            linkLatest(target, sniffed.ext());
        }
        Optional<int[]> dim = ImageDimensions.of(bytes);
        return new MediaArtifact(
                sha, target, root.relativize(target).toString(),
                sniffed.mimeType(), declaredMimeType, sniffed.kind(),
                bytes.length,
                dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                ArtifactSource.MATERIALIZED, true,
                (originalName == null || originalName.isBlank())
                        ? sha.substring(0, 8) + "." + sniffed.ext()
                        : originalName);
    }

    /**
     * 给最近一张图建一条稳定软链 {@code <artifactsDir>/latest.<ext>}，用户可直接 {@code open} 它。
     * ext 取实际嗅探结果而非写死 png——否则遇到 jpeg/webp 就名不副实。
     *
     * <p><b>任何失败都必须静默降级</b>：Windows 建符号链接要特权，某些文件系统压根不支持。
     * 这只是便利功能，不能让它把「artifact 已经落盘成功」这件事拖成失败——所以吞掉异常只记 debug，
     * 绝不向上抛。
     */
    private void linkLatest(Path target, String ext) {
        Path link = artifactsDir.resolve("latest." + ext);
        try {
            // 必须先删再建：createSymbolicLink 遇到已存在的目标直接抛 FileAlreadyExistsException，
            // 那样第一次建完就永远停在第一张图上。deleteIfExists 删的是链本身、不跟随，
            // 故上一张图已被 GC 清掉留下的悬空链同样能删掉。
            Files.deleteIfExists(link);
            // 存相对目标（只有文件名，同目录）：整个 artifacts 目录被搬走后链依然有效。
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            log.debug("latest 软链建立失败（不影响 artifact 落盘）：{}", e.toString());
        }
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(artifactsDir, ".art-", ".tmp");
        try {
            Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

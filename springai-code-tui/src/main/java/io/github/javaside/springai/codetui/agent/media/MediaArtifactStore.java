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

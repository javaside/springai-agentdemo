package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * artifacts 目录的体积上限淘汰：超过上限就按 mtime 从旧到新删到上限以下。
 *
 * <p><b>为什么现在必须做</b>：截图循环每次迭代产一张 2MB 的 4K PNG，而
 * {@code .codetui/artifacts/} 按项目共享、跨会话累积、{@code /clear} 也不清。
 * 一个调试会话跑几十轮就是几百 MB。
 *
 * <p><b>为什么不做引用扫描</b>：扫描要遍历所有会话文件，复杂度与出错面大得多。
 * 删掉仍被引用的旧图，后果只是模型 {@code Read} 时拿到「文件不存在」——可恢复。
 * 内容寻址在这里白送一个好处：同一张截图重复出现只占一份（sha 相同），
 * 静态页面反复截图不会累积。
 *
 * <p>在启动路径上调用，<b>绝不抛异常</b>——清理失败不该阻断启动。
 */
public final class ArtifactGc {

    private static final Logger log = LoggerFactory.getLogger(ArtifactGc.class);

    /** 默认上限 500MB。 */
    public static final long DEFAULT_MAX_BYTES = 500L * 1024 * 1024;

    private ArtifactGc() {}

    /** 目录总字节超过 {@code maxBytes} 则按 mtime 从旧到新删除，直到不超过为止。 */
    public static void sweep(Path artifactsDir, long maxBytes) {
        try {
            if (!Files.isDirectory(artifactsDir)) return;
            List<Path> files = new ArrayList<>();
            try (Stream<Path> s = Files.list(artifactsDir)) {
                // 必须先排掉软链再判 isRegularFile——后者<b>默认跟随</b>符号链接，会把
                // MediaArtifactStore 建的 latest.<ext> 当成一个普通文件：它的大小被重复计入总量
                // （指向的目标已经算过一次），而且链本身可能被删掉、或删掉目标留下悬空链。
                s.filter(p -> !Files.isSymbolicLink(p)).filter(Files::isRegularFile).forEach(files::add);
            }
            long total = 0;
            for (Path p : files) total += sizeOf(p);
            if (total <= maxBytes) return;

            files.sort(Comparator.comparingLong(ArtifactGc::mtimeOf));   // 最旧的排前面
            int deleted = 0;
            for (Path p : files) {
                if (total <= maxBytes) break;
                long sz = sizeOf(p);
                if (Files.deleteIfExists(p)) {
                    total -= sz;
                    deleted++;
                }
            }
            log.info("artifacts 清理：删除 {} 个旧文件，剩余约 {} MB", deleted, total / (1024 * 1024));
        } catch (IOException | RuntimeException e) {
            log.warn("artifacts 清理失败（不影响启动）：{}", e.toString());
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long mtimeOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}

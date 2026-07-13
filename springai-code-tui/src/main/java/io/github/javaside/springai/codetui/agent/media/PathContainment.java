// PathContainment.java
package io.github.javaside.springai.codetui.agent.media;

import java.nio.file.Files;
import java.nio.file.Path;

/** 把工具参数里的文件路径安全解析进项目 root——<b>解符号链接</b>后再做包含判断。
 *  修 macOS /tmp → /private/tmp 这类软链：normalize() 不解链，字符串段比较会把
 *  root=/tmp/gmt 下的 /private/tmp/gmt/x.png 误判为「越界」而拒绝处理。 */
final class PathContainment {
    private PathContainment() {}

    /** @return root 内的真实文件（已解符号链接），越界/不存在/非普通文件 → null。 */
    static Path resolveInRoot(String rawPath, Path root) {
        if (rawPath == null || rawPath.isBlank() || root == null) return null;
        try {
            Path p = Path.of(rawPath);
            if (!Files.isRegularFile(p)) return null;
            Path realFile = real(p);
            Path realRoot = real(root);
            return realFile.startsWith(realRoot) ? realFile : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** toRealPath 解符号链接；文件不存在等异常时回退 absolute+normalize（不解链，尽力而为）。 */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (java.io.IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}

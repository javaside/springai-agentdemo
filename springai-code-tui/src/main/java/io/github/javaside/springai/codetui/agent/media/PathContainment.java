// PathContainment.java
package io.github.javaside.springai.codetui.agent.media;

import java.nio.file.Files;
import java.nio.file.Path;

/** 把工具参数里的文件路径安全解析进项目 root——<b>解符号链接</b>后再做包含判断。
 *  修 macOS /tmp → /private/tmp 这类软链：normalize() 不解链，字符串段比较会把
 *  root=/tmp/gmt 下的 /private/tmp/gmt/x.png 误判为「越界」而拒绝处理。
 *
 *  <p><b>为什么是 public</b>：{@code ui} 层的用户附件也要把路径算成相对 root 的短路径写进引用块，
 *  与本类的口径必须<b>逐字一致</b>——两边各算一次的话，解链逻辑一改就会分家，
 *  而分家的后果是引用块里的 path 过不了解析器的包含校验、图静默消失。 */
public final class PathContainment {
    private PathContainment() {}

    /** @return root 内的真实文件（已解符号链接），越界/不存在/非普通文件 → null。 */
    public static Path resolveInRoot(String rawPath, Path root) {
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

    /** 相对 root 的短路径（给模型看/可再 Read）。两边都<b>解符号链接</b>后 relativize，
     *  避免 root 带链（/tmp）而 file 已解链（/private/tmp）时算出 `../../private/tmp/...` 这种
     *  跨越式路径。若仍越界（file 真的不在 root 下）→ 回退文件名，不泄漏绝对结构。 */
    public static String relativeToRoot(Path file, Path root) {
        Path rf = real(file), rr = real(root);
        if (rf.startsWith(rr)) return rr.relativize(rf).toString();
        return rf.getFileName().toString();
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

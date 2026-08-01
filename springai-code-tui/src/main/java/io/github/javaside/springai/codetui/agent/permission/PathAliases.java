package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 同一个文件的多种写法。<b>只给 deny / ask 规则与内置底线用</b>——allow 方向绝不放宽
 * （deny 误拦你看得见、能调整；allow 误放行不可逆）。
 *
 * <p><b>为什么不用 {@link Path#toRealPath}</b>：它对<b>尚不存在的文件</b>直接抛
 * {@code NoSuchFileException}，而「写一个还不存在的文件」是常态——期 1 正是因此
 * 整个放弃了符号链接解析。本类改为解析<b>最长的已存在祖先</b>再拼回剩余段：
 *
 * <pre>
 *   /tmp/link/sub/new.txt      （link → /etc，new.txt 尚不存在）
 *     最长已存在祖先 /tmp/link/sub → 解析为 /etc/sub
 *     拼回剩余段                   → /etc/sub/new.txt
 * </pre>
 *
 * 这样「目标文件尚不存在、但父目录经符号链接指向敏感位置」也能被 deny 命中——
 * 那正是两步绕过的形态。
 *
 * <p><b>绝不抛异常</b>：本类在判定热路径上被调用，任何异常都会把一次判定变成
 * 「内部出错 → 失败关闭成询问」，对用户表现为莫名其妙的弹窗。解析不了就退回原写法。
 *
 * <p><b>已知盲区</b>：目标本身是<b>悬空</b>符号链接（链接在、指向的文件还不存在）时，
 * {@code toRealPath()} 对它同样抛，本类退回只有原写法——即
 * {@code ln -s /etc/cron.d/evil x && Write x} 这条两步绕过<b>拦不住</b>。
 * 兜它的是「非只读 Bash 默认 ASK」，不是本类。
 */
public final class PathAliases {

    private PathAliases() {
    }

    /**
     * 该路径的全部写法，<b>原写法排第一</b>（调用方靠顺序做「先精确后放宽」的归因），去重。
     *
     * @param target 绝对路径；{@code null} 返回空列表，相对路径原样返回一个（无从解析）
     */
    public static List<Path> of(Path target) {
        List<Path> out = new ArrayList<>(2);
        if (target == null) {
            return out;
        }
        Path normalized = target.normalize();
        out.add(normalized);
        if (!normalized.isAbsolute()) {
            return out;                      // 相对路径无从解析：调用方交来的应已是绝对路径
        }
        Path resolved = resolveThroughSymlinks(normalized);
        if (resolved != null && !resolved.equals(normalized)) {
            out.add(resolved);
        }
        return out;
    }

    /**
     * 解析最长的已存在祖先，再把剩余段拼回去；解析不了返回 {@code null}。
     *
     * <p>失败情形（权限不足、循环链接、竞态删除）一律吞掉——见类注释「绝不抛异常」。
     */
    private static Path resolveThroughSymlinks(Path absolute) {
        Path existing = absolute;
        int climbed = 0;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            climbed++;
        }
        if (existing == null) {
            return null;                     // 一个祖先都不存在
        }
        try {
            Path real = existing.toRealPath();
            if (climbed == 0) {
                return real;
            }
            Path tail = existing.relativize(absolute);   // 把爬上去的那几段原样拼回来
            return real.resolve(tail).normalize();
        } catch (Exception e) {
            return null;                     // 权限不足 / 循环链接 / 竞态删除：退回原写法
        }
    }
}

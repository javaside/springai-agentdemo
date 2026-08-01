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
 * <p>目标本身是<b>悬空</b>链接（链接在、指向的文件还不存在）时 {@code toRealPath()} 同样抛，
 * 故那一步改为手工读链接内容——{@code ln -s /etc/cron.d/evil x && Write x} 是同一形状的
 * 两步绕过，不补的话新功能自带一个缺口。
 *
 * <h2>已知盲区（刻意留着，别当成 bug）</h2>
 * <ul>
 *   <li><b>链环、或超过 {@value #MAX_LINK_HOPS} 跳的链</b>：跳数用尽就放弃，退回只有原写法。
 *       链环本来也没有「真实路径」可言，而 8 跳远超真实链路深度。</li>
 *   <li><b>读不到链接内容</b>（权限不足、竞态删除）：同样退回原写法。宁可少给一个别名，
 *       也不能让一次判定变成内部错误。</li>
 *   <li><b>硬链接</b>：同一 inode 的另一个路径无从枚举——这不是解析问题，是文件系统不提供反查。</li>
 *   <li><b>macOS firmlink</b>：{@code /System/Volumes/Data/Users/…} 与 {@code /Users/…} 是同一份文件，
 *       但 {@code toRealPath()} 实测<b>不会</b>把前者收敛成后者（两者各自 real 到自己）。
 *       兜它的是 {@link DangerousPaths} 的结构匹配，不是本类。</li>
 * </ul>
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
     * 最多手工跳几格悬空链接。<b>存在的意义是保证退出</b>：链环（{@code a → b → a}）每跳都有进展、
     * 却永远到不了终点，只有跳数上限能把它停下来。8 跳远超真实链路深度。
     */
    private static final int MAX_LINK_HOPS = 8;

    /**
     * 解析最长的已存在祖先，再把剩余段拼回去；解析不了返回 {@code null}。
     *
     * <p>当那个「最长已存在祖先」自己是<b>悬空链接</b>时（链接在、指向的东西还不存在），
     * {@code toRealPath()} 对它同样抛——此时手工读一格链接内容再<b>整个重来一遍</b>。
     * 重来（而不是只拼一次）是为了让一次循环同时兜住三种形态：链接目标又是悬空链、
     * 链接目标的祖先自己带链（如 macOS 的 {@code /var}）、以及链环。
     *
     * <p>失败情形（权限不足、链环、竞态删除）一律吞掉——见类注释「绝不抛异常」。
     */
    private static Path resolveThroughSymlinks(Path absolute) {
        Path current = absolute;
        for (int hop = 0; hop <= MAX_LINK_HOPS; hop++) {
            Path existing = current;
            int climbed = 0;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
                climbed++;
            }
            if (existing == null) {
                return null;                 // 一个祖先都不存在
            }
            Path tail = climbed == 0 ? null : existing.relativize(current);  // 爬上去的那几段
            try {
                Path real = existing.toRealPath();
                return tail == null ? real : real.resolve(tail).normalize();
            } catch (Exception e) {
                // existing 自己是悬空链接或链环：toRealPath 对它照样抛。手工跳一格再来。
            }
            Path hopped = hopOnce(existing);
            if (hopped == null) {
                return null;                 // 不是链接（那失败另有原因，如权限不足）或读不出来
            }
            current = tail == null ? hopped : hopped.resolve(tail).normalize();
        }
        return null;                         // 跳数用尽：多半是链环，放弃
    }

    /**
     * 把一条悬空链接读成它指向的路径；不是链接或读不出来返回 {@code null}。
     *
     * <p>链接内容<b>可能是相对路径</b>，那时它相对的是<b>链接自己所在的目录</b>而非进程 cwd
     * ——按 cwd 解析会算出一个毫不相干的位置，等于没拦。
     */
    private static Path hopOnce(Path link) {
        try {
            if (!Files.isSymbolicLink(link)) {
                return null;
            }
            Path target = Files.readSymbolicLink(link);
            Path dir = link.getParent();
            Path resolved = (target.isAbsolute() || dir == null) ? target : dir.resolve(target);
            return resolved.normalize();
        } catch (Exception e) {
            return null;
        }
    }
}

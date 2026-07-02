package com.example.springai.codetui.ui;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 把 FileSystemTools 的 {@code edit} / {@code write} 工具入参（原始 JSON）渲染成 Claude Code 式的
 * 带真实行号的 diff 行序列（纯数据，不含任何 TamboUI/终端依赖，便于单测）。
 *
 * <p><b>为什么在这里读文件</b>：工具事件 {@code onToolStarted} 在<em>编辑发生前</em>触发，此刻磁盘上
 * 仍是旧内容，所以我们能读原文件、把 {@code old_string} 定位到真实起始行号，并对 old/new 逐行做 LCS
 * diff，保留未改动的上下文行——效果与 Claude Code 一致。文件读不到时优雅降级为「无真实行号的相对块」。
 *
 * <p>工具入参字段名（已核实自 FileSystemTools 的 {@code @ToolParam}）：
 * <ul>
 *   <li>{@code edit}  → {@code {file_path, old_string, new_string, replace_all}}</li>
 *   <li>{@code write} → {@code {file_path, content}}</li>
 * </ul>
 */
public final class DiffRenderer {

    /** 一行 diff 的语义类型（供上层上色）。 */
    public enum Type { HEADER, CONTEXT, ADD, DEL, TRUNCATED }

    /**
     * 一行渲染结果。
     *
     * @param type    行类型
     * @param oldNo   旧文件行号（1 起）；无则 null（如新增行、header）
     * @param newNo   新文件行号（1 起）；无则 null（如删除行、header）
     * @param text    行内容（HEADER 时为标题文本，如 {@code Update(path)}）
     */
    public record DiffLine(Type type, Integer oldNo, Integer newNo, String text) {}

    /** LCS 的规模上限：任一侧超过此行数则退化为「整块删+整块增」，避免 O(n·m) 爆炸。 */
    private static final int LCS_MAX = 800;

    /** diff 主体（不含 header）最多展示的行数，超出用一行 TRUNCATED 概括。 */
    private static final int BODY_CAP = 80;

    /** 变更区上下各保留几行未改动上下文（仅 edit：整文件 write 无需额外上下文）。 */
    private static final int CONTEXT = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Function<Path, List<String>> reader;

    /** 生产用：从磁盘读文件，读不到（不存在/IO 异常）返回 null。 */
    public DiffRenderer(Path root) {
        this(root, p -> {
            try {
                return Files.readAllLines(p);
            } catch (Exception e) {
                return null;
            }
        });
    }

    /** 可注入 reader 的构造（测试用）。 */
    public DiffRenderer(Path root, Function<Path, List<String>> reader) {
        this.root = root == null ? Path.of("").toAbsolutePath() : root.toAbsolutePath();
        this.reader = reader;
    }

    /** 该工具名是否是我们能渲染 diff 的文件写入工具。 */
    public static boolean isFileWrite(String toolName) {
        return "edit".equals(toolName) || "write".equals(toolName);
    }

    /**
     * 渲染。无法解析（非 JSON、缺字段、非目标工具）时返回空列表——调用方据此回退到普通工具行。
     */
    public List<DiffLine> render(String toolName, String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if ("edit".equals(toolName)) {
                return renderEdit(n);
            }
            if ("write".equals(toolName)) {
                return renderWrite(n);
            }
        } catch (Exception ignore) {
            // 解析失败：交由调用方回退
        }
        return List.of();
    }

    // ── edit：old_string → new_string，就地替换 ──────────────────────────
    private List<DiffLine> renderEdit(JsonNode n) {
        String pathStr = text(n, "file_path");
        String oldStr = text(n, "old_string");
        String newStr = text(n, "new_string");
        if (pathStr == null || oldStr == null || newStr == null) {
            return List.of();
        }
        List<String> oldLines = lines(oldStr);
        List<String> newLines = lines(newStr);
        List<String> file = reader.apply(Path.of(pathStr));

        List<DiffLine> out = new ArrayList<>();
        out.add(new DiffLine(Type.HEADER, null, null, "Update(" + rel(pathStr) + ")"));

        int at = (file == null) ? -1 : indexOfBlock(file, oldLines);
        if (at < 0) {
            // 读不到文件 / 定位不到：无真实行号，相对编号从 1 起
            emitDiff(out, oldLines, newLines, 1, 1);
            return cap(out);
        }

        // 上文（未改动）
        int ctxStart = Math.max(0, at - CONTEXT);
        for (int i = ctxStart; i < at; i++) {
            out.add(new DiffLine(Type.CONTEXT, i + 1, i + 1, file.get(i)));
        }
        // 变更主体
        emitDiff(out, oldLines, newLines, at + 1, at + 1);
        // 下文（未改动）
        int afterOld = at + oldLines.size();               // 旧文件中变更块之后的第一行(0基)
        int delta = newLines.size() - oldLines.size();
        int ctxEnd = Math.min(file.size(), afterOld + CONTEXT);
        for (int i = afterOld; i < ctxEnd; i++) {
            out.add(new DiffLine(Type.CONTEXT, i + 1, i + 1 + delta, file.get(i)));
        }
        return cap(out);
    }

    // ── write：整文件写入 ───────────────────────────────────────────────
    private List<DiffLine> renderWrite(JsonNode n) {
        String pathStr = text(n, "file_path");
        String content = text(n, "content");
        if (pathStr == null || content == null) {
            return List.of();
        }
        List<String> newLines = lines(content);
        List<String> file = reader.apply(Path.of(pathStr));

        List<DiffLine> out = new ArrayList<>();
        if (file == null) {
            // 新文件：整篇皆新增（全绿），行号 1..N
            out.add(new DiffLine(Type.HEADER, null, null, "Write(" + rel(pathStr) + ")"));
            for (int i = 0; i < newLines.size(); i++) {
                out.add(new DiffLine(Type.ADD, null, i + 1, newLines.get(i)));
            }
            return cap(out);
        }
        // 覆盖已有文件：与旧内容整篇做 diff
        out.add(new DiffLine(Type.HEADER, null, null, "Write(" + rel(pathStr) + ")"));
        emitDiff(out, file, newLines, 1, 1);
        return cap(out);
    }

    /**
     * 对 old/new 两组行做 LCS diff，把结果按 DEL/ADD/CONTEXT 追加到 out。
     * oldStart/newStart 为两侧首行的真实起始行号（1 起）。
     */
    private static void emitDiff(List<DiffLine> out, List<String> oldLines, List<String> newLines,
                                 int oldStart, int newStart) {
        if (oldLines.size() > LCS_MAX || newLines.size() > LCS_MAX) {
            // 规模过大：退化为整块删 + 整块增
            for (int i = 0; i < oldLines.size(); i++) {
                out.add(new DiffLine(Type.DEL, oldStart + i, null, oldLines.get(i)));
            }
            for (int j = 0; j < newLines.size(); j++) {
                out.add(new DiffLine(Type.ADD, null, newStart + j, newLines.get(j)));
            }
            return;
        }
        int m = oldLines.size();
        int k = newLines.size();
        int[][] dp = new int[m + 1][k + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = k - 1; j >= 0; j--) {
                dp[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        int oldNo = oldStart;
        int newNo = newStart;
        while (i < m && j < k) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                out.add(new DiffLine(Type.CONTEXT, oldNo++, newNo++, oldLines.get(i)));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                out.add(new DiffLine(Type.DEL, oldNo++, null, oldLines.get(i)));
                i++;
            } else {
                out.add(new DiffLine(Type.ADD, null, newNo++, newLines.get(j)));
                j++;
            }
        }
        while (i < m) {
            out.add(new DiffLine(Type.DEL, oldNo++, null, oldLines.get(i++)));
        }
        while (j < k) {
            out.add(new DiffLine(Type.ADD, null, newNo++, newLines.get(j++)));
        }
    }

    /** diff 主体（header 之外）超过 BODY_CAP 行则截断，补一行 TRUNCATED 概括剩余数量。 */
    private static List<DiffLine> cap(List<DiffLine> out) {
        int bodyStart = (!out.isEmpty() && out.get(0).type() == Type.HEADER) ? 1 : 0;
        int body = out.size() - bodyStart;
        if (body <= BODY_CAP) {
            return out;
        }
        List<DiffLine> capped = new ArrayList<>(out.subList(0, bodyStart + BODY_CAP));
        capped.add(new DiffLine(Type.TRUNCATED, null, null, "… 还有 " + (body - BODY_CAP) + " 行"));
        return capped;
    }

    /** 在 file 中查找与 block 完全相等的连续片段的起始下标（0 基）；找不到返回 -1。 */
    private static int indexOfBlock(List<String> file, List<String> block) {
        if (block.isEmpty()) {
            return -1;
        }
        int limit = file.size() - block.size();
        for (int i = 0; i <= limit; i++) {
            boolean hit = true;
            for (int j = 0; j < block.size(); j++) {
                if (!file.get(i + j).equals(block.get(j))) {
                    hit = false;
                    break;
                }
            }
            if (hit) {
                return i;
            }
        }
        return -1;
    }

    /** 按真实换行拆分；保留末尾空行语义（split(-1)）。空串视为单空行更贴合「写入一个空行」。 */
    private static List<String> lines(String s) {
        if (s.isEmpty()) {
            return List.of("");
        }
        return List.of(s.split("\n", -1));
    }

    /** 相对 root 展示路径；无法相对化（在 root 之外）则用绝对路径。 */
    private String rel(String pathStr) {
        try {
            Path p = Path.of(pathStr).toAbsolutePath();
            Path r = root.relativize(p);
            String s = r.toString();
            return s.startsWith("..") ? p.toString() : s;
        } catch (Exception e) {
            return pathStr;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || v.isNull()) ? null : v.asString();
    }
}

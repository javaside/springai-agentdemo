package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.DiffRenderer.DiffLine;
import io.github.javaside.springai.codetui.ui.DiffRenderer.Type;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DiffRenderer：edit/write 入参 → 带真实行号的 diff 行（LCS 上下文、行号定位、降级路径）。 */
class DiffRendererTest {

    private static final Path ROOT = Path.of("/proj").toAbsolutePath();

    /** 用内存「文件系统」注入 reader：key=绝对路径字符串，value=行列表。 */
    private static DiffRenderer withFiles(Map<String, List<String>> files) {
        Function<Path, List<String>> reader = p -> files.get(p.toAbsolutePath().toString());
        return new DiffRenderer(ROOT, reader);
    }

    private static DiffLine at(List<DiffLine> ls, int i) { return ls.get(i); }

    private static long count(List<DiffLine> ls, Type t) {
        return ls.stream().filter(l -> l.type() == t).count();
    }

    /** edit：能读到原文件时，改动行标真实行号，并带上下 CONTEXT。 */
    @Test
    void edit_withRealFile_realLineNumbers_andContext() {
        Path f = ROOT.resolve("A.java");
        List<String> file = List.of("l1", "l2", "old", "l4", "l5");   // "old" 在第 3 行
        DiffRenderer r = withFiles(Map.of(f.toString(), file));

        String json = """
                {"filePath":"%s","old_string":"old","new_string":"new1\\nnew2"}
                """.formatted(f);
        List<DiffLine> ls = r.render("Edit", json);   // 真实工具名首字母大写

        assertEquals(Type.HEADER, at(ls, 0).type());
        assertEquals("Update(A.java)", at(ls, 0).text(), "标题相对 root");

        DiffLine del = ls.stream().filter(l -> l.type() == Type.DEL).findFirst().orElseThrow();
        assertEquals("old", del.text());
        assertEquals(3, del.oldNo(), "删除行的真实旧行号");
        assertNull(del.newNo());

        List<DiffLine> adds = ls.stream().filter(l -> l.type() == Type.ADD).toList();
        assertEquals(List.of("new1", "new2"), adds.stream().map(DiffLine::text).toList());
        assertEquals(3, adds.get(0).newNo(), "新增起始新行号 = 原位置");
        assertEquals(4, adds.get(1).newNo());

        // 上文 l2(第2行) 应作为 CONTEXT 出现；下文 l4 的新行号因净 +1 而后移
        assertTrue(ls.stream().anyMatch(l -> l.type() == Type.CONTEXT && "l2".equals(l.text()) && l.oldNo() == 2));
        DiffLine l4 = ls.stream().filter(l -> "l4".equals(l.text())).findFirst().orElseThrow();
        assertEquals(4, l4.oldNo());
        assertEquals(5, l4.newNo(), "下文新行号随净增行数右移");
    }

    /** edit：读不到文件（null）时降级——无 CONTEXT，相对行号从 1 起。 */
    @Test
    void edit_fileUnreadable_degradesToRelative() {
        Path f = ROOT.resolve("Ghost.java");
        DiffRenderer r = withFiles(Map.of());   // 空：reader 返回 null

        String json = "{\"filePath\":\"" + f + "\",\"old_string\":\"a\",\"new_string\":\"b\"}";
        List<DiffLine> ls = r.render("Edit", json);

        assertEquals(Type.HEADER, at(ls, 0).type());
        assertEquals(0, count(ls, Type.CONTEXT), "读不到文件则无上下文");
        DiffLine del = ls.stream().filter(l -> l.type() == Type.DEL).findFirst().orElseThrow();
        assertEquals(1, del.oldNo(), "降级：相对行号从 1 起");
    }

    /** LCS：共享行识别为 CONTEXT，只对差异部分增删。 */
    @Test
    void edit_lcs_keepsSharedLinesAsContext() {
        Path f = ROOT.resolve("B.txt");
        List<String> file = List.of("keep", "drop", "tail");
        DiffRenderer r = withFiles(Map.of(f.toString(), file));

        // old="keep\ndrop\ntail" → new="keep\nadd\ntail"：keep/tail 共享
        String json = "{\"filePath\":\"" + f + "\",\"old_string\":\"keep\\ndrop\\ntail\","
                + "\"new_string\":\"keep\\nadd\\ntail\"}";
        List<DiffLine> ls = r.render("Edit", json);

        DiffLine del = ls.stream().filter(l -> l.type() == Type.DEL).findFirst().orElseThrow();
        assertEquals("drop", del.text());
        DiffLine add = ls.stream().filter(l -> l.type() == Type.ADD).findFirst().orElseThrow();
        assertEquals("add", add.text());
        // keep(行1)、tail(行3) 作为共享行 CONTEXT
        assertTrue(ls.stream().anyMatch(l -> l.type() == Type.CONTEXT && "keep".equals(l.text())));
        assertTrue(ls.stream().anyMatch(l -> l.type() == Type.CONTEXT && "tail".equals(l.text())));
    }

    /** write 新文件（读不到）：整篇全 ADD，行号 1..N。 */
    @Test
    void write_newFile_allAdd() {
        Path f = ROOT.resolve("New.txt");
        DiffRenderer r = withFiles(Map.of());

        String json = "{\"filePath\":\"" + f + "\",\"content\":\"a\\nb\\nc\"}";
        List<DiffLine> ls = r.render("Write", json);

        assertEquals("Write(New.txt)", at(ls, 0).text());
        assertEquals(3, count(ls, Type.ADD));
        assertEquals(0, count(ls, Type.DEL));
        DiffLine first = ls.stream().filter(l -> l.type() == Type.ADD).findFirst().orElseThrow();
        assertEquals(1, first.newNo());
    }

    /** write 覆盖已有文件：与旧内容做 diff（有 DEL 有 ADD）。 */
    @Test
    void write_overwriteExisting_diffsAgainstOld() {
        Path f = ROOT.resolve("Exist.txt");
        DiffRenderer r = withFiles(Map.of(f.toString(), List.of("x", "y")));

        String json = "{\"filePath\":\"" + f + "\",\"content\":\"x\\nz\"}";   // y→z
        List<DiffLine> ls = r.render("Write", json);

        assertTrue(count(ls, Type.DEL) >= 1, "覆盖已有文件应体现删除");
        assertTrue(count(ls, Type.ADD) >= 1, "覆盖已有文件应体现新增");
        assertTrue(ls.stream().anyMatch(l -> l.type() == Type.CONTEXT && "x".equals(l.text())), "未改行为上下文");
    }

    /** 非文件写入工具 / 非法 JSON：返回空列表（调用方回退单行）。 */
    @Test
    void nonFileWrite_orBadJson_returnsEmpty() {
        DiffRenderer r = withFiles(Map.of());
        assertTrue(r.render("Grep", "{\"pattern\":\"x\"}").isEmpty(), "非 Edit/Write 返回空");
        assertTrue(r.render("Edit", "not json").isEmpty(), "坏 JSON 返回空");
        assertTrue(r.render("Edit", "{\"filePath\":\"/p\"}").isEmpty(), "缺字段返回空");
    }

    /** isFileWrite 判定：工具名大小写不敏感（真实为 Edit/Write）。 */
    @Test
    void isFileWrite_recognizesEditWrite_caseInsensitive() {
        assertTrue(DiffRenderer.isFileWrite("Edit"), "真实工具名首字母大写");
        assertTrue(DiffRenderer.isFileWrite("Write"));
        assertTrue(DiffRenderer.isFileWrite("edit"), "小写也认（容错）");
        assertTrue(DiffRenderer.isFileWrite("write"));
        assertFalse(DiffRenderer.isFileWrite("Read"));
        assertFalse(DiffRenderer.isFileWrite("Grep"));
    }

    /** 字段名容错：下划线 file_path 也能解析（老版本 / 其它命名）。 */
    @Test
    void fieldAlias_underscoreFilePath_alsoWorks() {
        Path f = ROOT.resolve("Alias.txt");
        DiffRenderer r = withFiles(Map.of());
        String json = "{\"file_path\":\"" + f + "\",\"content\":\"hi\"}";
        List<DiffLine> ls = r.render("Write", json);
        assertEquals("Write(Alias.txt)", at(ls, 0).text(), "file_path 别名可用");
        assertEquals(1, count(ls, Type.ADD));
    }

    /** 主体超长时截断并补一行 TRUNCATED。 */
    @Test
    void largeAdd_isTruncated() {
        Path f = ROOT.resolve("Big.txt");
        DiffRenderer r = withFiles(Map.of());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("line").append(i).append("\\n");
        sb.setLength(sb.length() - 2);   // 去掉末尾 \n
        String json = "{\"filePath\":\"" + f + "\",\"content\":\"" + sb + "\"}";

        List<DiffLine> ls = r.render("Write", json);
        assertEquals(1, count(ls, Type.TRUNCATED), "超长应补一行截断概括");
        assertEquals(Type.TRUNCATED, ls.get(ls.size() - 1).type(), "截断行在末尾");
    }
}

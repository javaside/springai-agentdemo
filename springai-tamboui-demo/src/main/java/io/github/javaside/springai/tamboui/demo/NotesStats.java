package io.github.javaside.springai.tamboui.demo;

/**
 * 第 5 课的笔记统计（纯逻辑）：字符数（按<b>码点</b>计，与第 2 课口径一致——
 * emoji 等增补平面字符算 1 个字符而非 2 个 UTF-16 单元）/ 总行数 / 非空行数。
 */
public record NotesStats(int chars, int lines, int nonEmptyLines) {

    public static NotesStats of(String text) {
        if (text.isEmpty()) {
            return new NotesStats(0, 1, 0);
        }
        String[] rows = text.split("\n", -1);
        int nonEmpty = 0;
        for (String row : rows) {
            if (!row.isBlank()) {
                nonEmpty++;
            }
        }
        return new NotesStats(text.codePointCount(0, text.length()), rows.length, nonEmpty);
    }
}

package io.github.javaside.springai.tamboui.demo;

/**
 * 粘贴文本统计（示例③使用）：字符数、行数、\r\n 出现次数。
 *
 * <p>行数口径：\n、\r\n、孤立 \r 都算一次换行——与终端实际行为一致（老 Mac 的孤立 CR
 * 也是换行），且让「CR 是否被上游泄漏/吃掉」可以被当场验证。</p>
 *
 * @param chars    全部字符个数（含换行符本身）
 * @param lines    按 \r\n|\r|\n 切分后的段数（空文本为 1）
 * @param crlfCount "\r\n" 出现次数
 */
public record PasteStats(int chars, int lines, int crlfCount) {

    public static PasteStats of(String text) {
        int crlf = 0;
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;   // \r\n 整体算一次换行，跳过 \n
                }
                lines++;
            } else if (c == '\n') {
                lines++;
            }
        }
        return new PasteStats(text.length(), lines, crlf);
    }

    @Override
    public String toString() {
        return chars + " 字符 / " + lines + " 行 / CRLF×" + crlfCount;
    }
}

package dev.tamboui.inline;

/**
 * 测试用迷你 VT 状态机：把 InlineDisplay 发出的字节序列重放成屏幕画面，
 * 让单测可以断言「最终画面对不对」——这是纯字节子串断言做不到的
 * （同族教训见 InlineDisplayDiffTest#shrinkingWithStableTopAndBottomDeletesExactlyTheChangedMiddleSection：
 * 纯字节测不出 DL 落在哪行，这里同理测不出残影/错位）。
 *
 * <p><b>只实现 InlineDisplay 实际会发的序列</b>：CUU/CUD/CUF/CUB、EL(0)、IL、DL、
 * CR、LF（含底行滚屏）、可打印字符；SGR 与其余 CSI 静默忽略。
 * 不做双宽/continuation（本模块的渲染测试内容全用窄字符），不做滚动区。
 * 真实终端语义以 pyte 冒烟为准，这里只需足够忠实于 VT100 的这几条原语。
 */
final class MiniVt {

    private final int width;
    private final int height;
    private final char[][] cells;
    private int cursorX;
    private int cursorY;

    MiniVt(int width, int height) {
        this.width = width;
        this.height = height;
        this.cells = new char[height][width];
        for (int y = 0; y < height; y++) java.util.Arrays.fill(cells[y], ' ');
    }

    /** 逐段重放：转义序列一条一条走，普通文本逐字符写。 */
    void feed(String data) {
        int i = 0;
        int n = data.length();
        while (i < n) {
            char c = data.charAt(i);
            if (c == '\u001b') {
                i = feedEscape(data, i);
            } else if (c == '\r') {
                cursorX = 0;
                i++;
            } else if (c == '\n') {
                lineFeed();
                i++;
            } else if (c == '\b') {
                if (cursorX > 0) cursorX--;
                i++;
            } else {
                if (cursorX >= width) {          // pending wrap 简化：折行
                    cursorX = 0;
                    lineFeed();
                }
                cells[cursorY][cursorX] = c;
                cursorX++;
                i++;
            }
        }
    }

    /** @return 消费到哪（含序列结尾） */
    private int feedEscape(String data, int start) {
        int i = start + 1;
        if (i >= data.length()) return i;
        if (data.charAt(i) != '[') return i;     // 非 CSI（本模块不发）忽略
        i++;
        int paramStart = i;
        while (i < data.length() && "0123456789;?".indexOf(data.charAt(i)) >= 0) i++;
        if (i >= data.length()) return i;
        char cmd = data.charAt(i);
        String param = data.substring(paramStart, i);
        int p1 = parseIntOrDefault(param, 1);
        switch (cmd) {
            case 'A' -> cursorY = Math.max(0, cursorY - p1);
            case 'B' -> cursorY = Math.min(height - 1, cursorY + p1);
            case 'C' -> cursorX = Math.min(width - 1, cursorX + p1);
            case 'D' -> cursorX = Math.max(0, cursorX - p1);
            case 'K' -> java.util.Arrays.fill(cells[cursorY], cursorX, width, ' ');
            case 'L' -> insertLines(p1);
            case 'M' -> deleteLines(p1);
            case 'H', 'f' -> {
                // 只支持无参 home（本模块不发带参 CUP）
                cursorX = 0;
                cursorY = 0;
            }
            default -> { /* SGR(m) 与其余（?h/?l 等）忽略 */ }
        }
        return i + 1;
    }

    private void lineFeed() {
        if (cursorY == height - 1) {
            System.arraycopy(cells, 1, cells, 0, height - 1);
            java.util.Arrays.fill(cells[height - 1], ' ');
        } else {
            cursorY++;
        }
    }

    private void insertLines(int count) {
        for (int y = height - 1; y >= cursorY + count; y--) cells[y] = cells[y - count].clone();
        for (int y = cursorY; y < Math.min(height, cursorY + count); y++) java.util.Arrays.fill(cells[y], ' ');
    }

    private void deleteLines(int count) {
        for (int y = cursorY; y + count < height; y++) cells[y] = cells[y + count].clone();
        for (int y = Math.max(cursorY, height - count); y < height; y++) java.util.Arrays.fill(cells[y], ' ');
    }

    private static int parseIntOrDefault(String param, int def) {
        if (param.isEmpty() || param.startsWith("?")) return def;
        int semi = param.indexOf(';');
        String head = semi < 0 ? param : param.substring(0, semi);
        if (head.isEmpty()) return def;
        try {
            return Integer.parseInt(head);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    String[] display() {
        String[] out = new String[height];
        for (int y = 0; y < height; y++) out[y] = new String(cells[y]);
        return out;
    }

    /** 找一行的全文（去尾部空白）；找不到返回 null。 */
    String findRow(String contained) {
        for (String row : display()) {
            if (row.contains(contained)) return row.stripTrailing();
        }
        return null;
    }
}

package io.github.javaside.springai.tamboui;

/**
 * 菜单输入解析（纯逻辑，脱离终端可单测）。
 *
 * <p>口径：trim 后必须是 1..count 的整数才有效；0 表示退出；其余（非数字、空白、越界、
 * 负数、null）一律 -1，由菜单循环提示重输而不是抛异常——交互入口的第一道防线。</p>
 */
public final class MenuInput {

    private MenuInput() {
    }

    /**
     * 解析一行菜单输入。
     *
     * @param line  用户输入的原行（可为 null）
     * @param count 可选示例总数
     * @return 1..count 的有效序号；0 退出；-1 非法
     */
    public static int parse(String line, int count) {
        if (line == null) {
            return -1;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        final int choice;
        try {
            choice = Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (choice < 0 || choice > count) {
            return -1;
        }
        return choice;
    }
}

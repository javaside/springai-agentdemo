package io.github.javaside.springai.tamboui;

/**
 * 子进程启动参数解析：{@code --lesson N}（N 为 1..课程数）→ N；其余（无参/缺参/非数字/
 * 越界/非正）→ -1 表示进入菜单模式。
 */
public final class LessonArgs {

    private LessonArgs() {
    }

    public static int parse(String[] args) {
        if (args == null || args.length != 2 || !"--lesson".equals(args[0])) {
            return -1;
        }
        try {
            int lesson = Integer.parseInt(args[1]);
            return lesson >= 1 && lesson <= TambouiDemoApplication.lessonCount() ? lesson : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

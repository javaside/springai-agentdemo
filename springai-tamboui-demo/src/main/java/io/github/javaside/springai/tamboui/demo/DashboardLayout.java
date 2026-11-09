package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;

import java.util.List;

/**
 * 第 3 课的布局定义（纯逻辑、可单测）：把整屏切成三段，中段再左右切分。
 *
 * <p>约束式布局的语言只有一句：<b>给一个 Rect 和一组 {@link Constraint}，
 * {@link Layout#split} 返回切好的子 Rect 列表</b>——length(n) 固定行/列数、
 * percentage(n) 按比例、fill() 吃掉剩余、min/max 设上下限。切分可嵌套：
 * 拿到子 Rect 再 split 就是多面板布局的全部秘密。</p>
 */
public final class DashboardLayout {

    private DashboardLayout() {
    }

    /** 整屏三段：固定 3 行头 + 60% 中段 + 剩余全给尾。 */
    public static List<Rect> split(Rect screen) {
        return Layout.vertical()
                .constraints(Constraint.length(3), Constraint.percentage(60), Constraint.fill())
                .split(screen);
    }

    /** 中段左右两栏：List 占 60%，Gauge 吃剩余。 */
    public static List<Rect> splitMiddle(Rect middle) {
        return Layout.horizontal()
                .constraints(Constraint.percentage(60), Constraint.fill())
                .split(middle);
    }
}

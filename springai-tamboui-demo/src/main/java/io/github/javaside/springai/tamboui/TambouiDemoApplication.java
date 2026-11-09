package io.github.javaside.springai.tamboui;

import java.util.Comparator;
import io.github.javaside.springai.tamboui.demo.BufferBlockDemo;
import io.github.javaside.springai.tamboui.demo.InlineEventsDemo;
import io.github.javaside.springai.tamboui.demo.ToolkitChatDemo;
import io.github.javaside.springai.tamboui.demo.TextStylesWidthDemo;
import java.util.List;
import java.util.Scanner;

/**
 * springai-tamboui-demo 入口 —— 纯 Java 菜单，逐个运行 TamboUI 示例。
 *
 * <p>对照 core-demo 的 {@code ConsoleMenu}：这里同样没有 Spring、没有依赖注入，
 * 示例列表在 main 里手动组装（本模块不跨模块依赖 core-demo，故自写简版）。
 * 输入合法性全部委托 {@link MenuInput}（纯逻辑、有单测），菜单循环只管分派。</p>
 */
public final class TambouiDemoApplication {

    public static void main(String[] args) {
        // 示例注册点：按 order 顺序展示（order 递增即教学递进）。
        List<TambouiDemo> demos = List.of(
                new TextStylesWidthDemo(),
                new BufferBlockDemo(),
                new InlineEventsDemo(),
                new ToolkitChatDemo()
        );

        new TambouiDemoApplication(demos).run();
    }

    private final List<TambouiDemo> demos;

    private TambouiDemoApplication(List<TambouiDemo> demos) {
        this.demos = demos.stream()
                .sorted(Comparator.comparingInt(TambouiDemo::order))
                .toList();
    }

    private void run() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                printMenu();
                System.out.print("请输入序号（0 退出）：");
                String line = scanner.hasNextLine() ? scanner.nextLine() : "0";
                int choice = MenuInput.parse(line, demos.size());
                if (choice == -1) {
                    System.out.println("⚠️  请输入 0 到 " + demos.size() + " 之间的数字。\n");
                    continue;
                }
                if (choice == 0) {
                    System.out.println("再见！");
                    return;
                }
                TambouiDemo demo = demos.get(choice - 1);
                System.out.println("\n========== ▶ " + demo.title() + " ==========");
                try {
                    demo.run();
                } catch (Exception e) {
                    System.out.println("\n❌ 运行出错：" + e.getMessage());
                    System.out.println("   （常见原因：在 IDE 控制台/管道等非真实终端里运行——请换真实终端）");
                }
                System.out.println();
            }
        }
    }

    private void printMenu() {
        System.out.println("TamboUI 示例（第⑤步：终端 UI 框架，基于 TamboUI 0.4.0 实测 API）");
        for (TambouiDemo demo : demos) {
            System.out.println("  " + demo.order() + ". " + demo.title());
        }
    }
}

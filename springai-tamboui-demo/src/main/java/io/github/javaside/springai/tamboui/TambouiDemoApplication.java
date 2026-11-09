package io.github.javaside.springai.tamboui;

import java.util.Comparator;
import io.github.javaside.springai.tamboui.demo.HelloTuiDemo;
import io.github.javaside.springai.tamboui.demo.TextStyleDemo;
import io.github.javaside.springai.tamboui.demo.InlineEventsDemo;
import io.github.javaside.springai.tamboui.demo.ToolkitChatDemo;
import java.util.List;
import java.util.Scanner;
import java.util.function.Supplier;

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
        // 供给方式是 Supplier：每次运行取<b>新实例</b>——示例②③④持有可变状态（帧号/仪表盘/
        // 流式会话），共享实例会把上一次运行的残留带进下一次（示例④流式中途退出后重跑会
        // 永久卡在「生成中」，终审修复）。DemoSuppliersTest 钉住这一供给方式。
        List<Supplier<TambouiDemo>> suppliers = demoSuppliers();

        new TambouiDemoApplication(suppliers).run();
    }

    /** 示例清单（供给方式：每次 get() 产生新实例）。 */
    static List<Supplier<TambouiDemo>> demoSuppliers() {
        return List.of(
                HelloTuiDemo::new,
                TextStyleDemo::new,
                InlineEventsDemo::new,
                ToolkitChatDemo::new
        );
    }

    private final List<Supplier<TambouiDemo>> suppliers;
    private final List<TambouiDemo> demos;

    private TambouiDemoApplication(List<Supplier<TambouiDemo>> suppliers) {
        // 展示与分派同一份排序（按 order）——否则注册乱序时菜单显示与实际运行会错位
        this.suppliers = suppliers.stream()
                .sorted(Comparator.comparingInt(s -> s.get().order()))
                .toList();
        this.demos = this.suppliers.stream()
                .map(Supplier::get)   // 仅用于菜单展示（标题/序号）；真正运行时再取新实例
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
                TambouiDemo demo = suppliers.get(choice - 1).get();   // 运行时取新实例
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

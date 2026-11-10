package io.github.javaside.springai.tamboui;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;
import java.util.function.Supplier;

/**
 * springai-tamboui-demo 入口 —— 课程菜单 + 每课独立 JVM。
 *
 * <p><b>为什么每课开子进程</b>：TUI 应用独占终端是它的天性——上游 0.4.0 在同一进程里
 * 连续创建/关闭多个 TuiRunner/InlineTuiRunner 时，前一个应用的 jline 读线程残留会与
 * 后续读取方竞争 stdin（实测：全屏课退出后菜单收到的第一行丢首字节）。让每课在独立
 * JVM 里运行（{@code --lesson N}），终端状态随进程生灭，问题从根上不存在——这也是
 * 真实 TUI 应用的交付形态。子进程经 {@code java -cp <java.class.path>} 启动：
 * jar 的 manifest Class-Path（lib/）与 IDE/mvn 的完整 classpath 两种场景都被覆盖。</p>
 *
 * <p>课程清单在 {@link #demoSuppliers()} 一处注册（菜单与子进程共用）；
 * 输入合法性委托 {@link MenuInput}（纯逻辑、有单测）。</p>
 */
public final class TambouiDemoApplication {

    public static void main(String[] args) {
        int lesson = LessonArgs.parse(args);
        if (lesson > 0) {
            runLessonDirectly(lesson);   // 子进程模式：直接运行该课，无菜单
            return;
        }
        new TambouiDemoApplication().runMenu();
    }

    /** 示例清单（供给方式：每次 get() 产生新实例）。菜单与 --lesson 子进程共用这份注册。 */
    static List<Supplier<TambouiDemo>> demoSuppliers() {
        return List.of(
                io.github.javaside.springai.tamboui.demo.CreateTuiDemo::new,
                io.github.javaside.springai.tamboui.demo.HelloTuiDemo::new,
                io.github.javaside.springai.tamboui.demo.TextStyleDemo::new,
                io.github.javaside.springai.tamboui.demo.WidgetsGalleryDemo::new,
                io.github.javaside.springai.tamboui.demo.WidgetsLayoutDemo::new,
                io.github.javaside.springai.tamboui.demo.EventsDemo::new,
                io.github.javaside.springai.tamboui.demo.ToolkitDslDemo::new,
                io.github.javaside.springai.tamboui.demo.InlineChatDemo::new
        );
    }

    static int lessonCount() {
        return demoSuppliers().size();
    }

    private static void runLessonDirectly(int lesson) {
        TambouiDemo demo = demoSuppliers().get(lesson - 1).get();
        try {
            demo.run();
        } catch (Exception e) {
            System.out.println("\n❌ 运行出错：" + e.getMessage());
            System.out.println("   （常见原因：在 IDE 控制台/管道等非真实终端里运行——请换真实终端）");
        }
    }

    private void runMenu() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                printMenu();
                System.out.print("请输入序号（0 退出）：");
                String line = scanner.hasNextLine() ? scanner.nextLine() : "0";
                int choice = MenuInput.parse(line, lessonCount());
                if (choice == -1) {
                    System.out.println("⚠️  请输入 0 到 " + lessonCount() + " 之间的数字。\n");
                    continue;
                }
                if (choice == 0) {
                    System.out.println("再见！");
                    return;
                }
                launchLessonInChildProcess(choice);
                System.out.println();
            }
        }
    }

    /** 在独立 JVM 里运行一课（继承 stdio，等待其退出后回到菜单）。 */
    private void launchLessonInChildProcess(int choice) {
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                ProcessHandle.current().info().command().orElse("java"),
                "-cp", classpath,
                TambouiDemoApplication.class.getName(),
                "--lesson", String.valueOf(choice))
                .inheritIO();
        try {
            Process child = pb.start();
            child.waitFor();
        } catch (IOException e) {
            System.out.println("❌ 无法启动子进程：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printMenu() {
        System.out.println("TamboUI 示例（第⑤步：终端 UI 框架课程，基于 TamboUI 0.4.0 实测 API）");
        demoSuppliers().stream()
                .map(Supplier::get)
                .sorted(Comparator.comparingInt(TambouiDemo::order))
                .forEach(d -> System.out.println("  " + d.order() + ". " + d.title()));
    }
}

package com.example.springai.agent;

import com.example.springai.agent.demo.Demo;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * 交互式菜单：启动后列出所有 {@link Demo}，输入数字执行。
 * 通过构造器注入 {@code List<Demo>} 自动收集所有示例 Bean。
 */
@Component
public class DemoMenuRunner implements ApplicationRunner {

    private final List<Demo> demos;

    public DemoMenuRunner(List<Demo> demos) {
        this.demos = demos.stream()
                .sorted(Comparator.comparingInt(Demo::order))
                .toList();
    }

    @Override
    public void run(ApplicationArguments args) {
        warnIfApiKeyMissing();
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                printMenu();
                System.out.print("请输入序号（0 退出）：");
                String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "0";
                if (line.isEmpty()) {
                    continue;
                }
                int choice;
                try {
                    choice = Integer.parseInt(line);
                } catch (NumberFormatException e) {
                    System.out.println("⚠️  请输入数字。\n");
                    continue;
                }
                if (choice == 0) {
                    System.out.println("再见！");
                    return;
                }
                if (choice < 1 || choice > demos.size()) {
                    System.out.println("⚠️  序号超出范围。\n");
                    continue;
                }
                Demo demo = demos.get(choice - 1);
                System.out.println("\n========== ▶ " + demo.title() + " ==========");
                long start = System.currentTimeMillis();
                try {
                    demo.run();
                } catch (Exception e) {
                    System.out.println("\n❌ 运行出错：" + e.getMessage());
                    System.out.println("   （常见原因：未设置 DEEPSEEK_API_KEY、网络不通、或 MCP 示例未配置外部 Server）");
                }
                System.out.println("========== ✔ 完成（耗时 " + (System.currentTimeMillis() - start) + " ms） ==========\n");
            }
        }
    }

    private void printMenu() {
        System.out.println("==================== Spring AI 智能体（Agent）示例 ====================");
        for (int i = 0; i < demos.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, demos.get(i).title());
        }
        System.out.println("  0. 退出");
        System.out.println("====================================================================");
    }

    private void warnIfApiKeyMissing() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("\n⚠️  未检测到环境变量 DEEPSEEK_API_KEY，调用 DeepSeek 的示例会失败。");
            System.out.println("   设置方式（macOS/Linux）：export DEEPSEEK_API_KEY=你的key  然后重新启动。\n");
        }
    }
}

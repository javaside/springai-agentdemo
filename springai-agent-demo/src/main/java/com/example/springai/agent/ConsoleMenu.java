package com.example.springai.agent;

import com.example.springai.agent.demo.Demo;

import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

/**
 * 纯 Java 的交互式控制台菜单（与 core 模块同理，无 Spring）。
 */
public class ConsoleMenu {

    private final String title;
    private final List<Demo> demos;

    public ConsoleMenu(String title, List<Demo> demos) {
        this.title = title;
        this.demos = demos.stream()
                .sorted(Comparator.comparingInt(Demo::order))
                .toList();
    }

    public void run() {
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
                    System.out.println("   （常见原因：未设置 DEEPSEEK_API_KEY、网络不通）");
                }
                System.out.println("========== ✔ 完成（耗时 " + (System.currentTimeMillis() - start) + " ms） ==========\n");
            }
        }
    }

    private void printMenu() {
        System.out.println("==================== " + title + " ====================");
        for (int i = 0; i < demos.size(); i++) {
            System.out.printf("  %d. %s%n", i + 1, demos.get(i).title());
        }
        System.out.println("  0. 退出");
        System.out.println("==========================================================");
    }
}

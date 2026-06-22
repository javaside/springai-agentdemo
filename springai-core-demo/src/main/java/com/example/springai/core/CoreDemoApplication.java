package com.example.springai.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * springai-core-demo 启动类。
 *
 * <p>这是一个“控制台应用”：启动后会弹出一个菜单（见 {@link DemoMenuRunner}），
 * 你输入数字选择要运行的 Spring AI 示例，看完一个还能继续选下一个，不用改代码、不用重启。
 *
 * <p>运行前请先设置环境变量 DEEPSEEK_API_KEY（DeepSeek 的 API Key）。
 */
@SpringBootApplication
public class CoreDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreDemoApplication.class, args);
    }
}

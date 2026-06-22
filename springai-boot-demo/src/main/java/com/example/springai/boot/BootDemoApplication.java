package com.example.springai.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * springai-boot-demo 启动类。
 *
 * <p>本模块用 Spring Boot + Spring AI 的 starter 演示「自动装配（auto-configuration）」。
 * 对照 springai-core-demo / springai-agent-demo 的 main 方法里那一大段“手动 new”，
 * 这里你会发现：ChatModel、EmbeddingModel、ChatClient.Builder 等通通不用自己创建，
 * 声明类型就能注入——这些都是 starter 在启动时自动配置好的。
 */
@SpringBootApplication
public class BootDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BootDemoApplication.class, args);
    }
}

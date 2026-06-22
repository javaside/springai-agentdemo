package com.example.springai.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * springai-agent-demo 启动类。
 *
 * <p>本模块演示 Spring AI 的“智能体（Agent）”能力：让模型不只是聊天，还能
 * 调用工具、记住上下文、分多步完成任务，以及通过 MCP 接入外部工具。
 *
 * <p>同样是控制台菜单应用：启动后输入数字选择示例。运行前请设置 DEEPSEEK_API_KEY。
 */
@SpringBootApplication
public class AgentDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentDemoApplication.class, args);
    }
}

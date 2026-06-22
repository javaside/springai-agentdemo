package com.example.springai.boot.demo;

/**
 * 示例统一接口：实现本接口 + 标注 {@code @Component} 即可自动出现在菜单里
 * （菜单 {@code DemoMenuRunner} 用 Spring 注入 {@code List<Demo>} 自动收集——这也是“自动装配”的体现）。
 */
public interface Demo {

    String title();

    int order();

    void run() throws Exception;
}

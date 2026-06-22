package com.example.springai.agent.demo;

/**
 * 示例统一接口（与 core 模块同理）：实现本接口 + 标注 {@code @Component} 即可自动出现在菜单里。
 */
public interface Demo {

    /** 菜单标题。 */
    String title();

    /** 菜单排序（越小越靠前）。 */
    int order();

    /** 示例逻辑。 */
    void run() throws Exception;
}

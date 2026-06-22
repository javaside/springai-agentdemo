package com.example.springai.core.demo;

/**
 * 每一个 Spring AI 示例都实现这个接口，并标注 {@code @Component} 注册为 Spring Bean。
 *
 * <p>菜单 {@code DemoMenuRunner} 会自动收集所有 Demo 实现，按 {@link #order()} 排序后列成菜单。
 * 想新增一个示例：写个类实现本接口 + 加 {@code @Component} 即可，菜单会自动出现，无需改其它代码。
 */
public interface Demo {

    /** 菜单里显示的标题。 */
    String title();

    /** 菜单排序（数字越小越靠前），方便按学习难度排列。 */
    int order();

    /** 示例的实际逻辑。 */
    void run() throws Exception;
}

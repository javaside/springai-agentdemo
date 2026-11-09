package io.github.javaside.springai.tamboui;

/**
 * 一个 TamboUI 示例。对照 core-demo 的 {@code Demo} 接口：菜单按 {@link #order()} 排序、
 * 显示 {@link #title()}、选中后调 {@link #run()}。
 *
 * <p>示例①③④会进入行内 TUI 模式接管终端，因此约定 {@code run()} 返回即代表示例完全收场、
 * 终端已恢复普通模式（示例内部自行 try-with-resources 关闭 runner）。</p>
 */
public interface TambouiDemo {

    /** 菜单序号（1 起，与展示顺序一致）。 */
    int order();

    /** 菜单标题（一句话说明本示例教什么）。 */
    String title();

    /** 运行示例。进入 TUI 模式的示例在返回前须恢复终端。 */
    void run() throws Exception;
}

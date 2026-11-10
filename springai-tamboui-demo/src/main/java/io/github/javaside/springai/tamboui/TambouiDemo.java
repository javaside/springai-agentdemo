package io.github.javaside.springai.tamboui;

/**
 * 一个 TamboUI 示例。对照 core-demo 的 {@code Demo} 接口：菜单按 {@link #order()} 排序、
 * 显示 {@link #title()}、选中后调 {@link #run()}。
 *
 * <p>每课运行期间都会接管终端（第 1–7 课全屏备用屏幕、第 8 课行内模式），因此约定
 * {@code run()} 返回即代表本课完全收场、终端已复原（各课自行 try-with-resources
 * 关闭 runner）。实际运行时每课在独立子进程里执行，见 {@code TambouiDemoApplication}。</p>
 */
public interface TambouiDemo {

    /** 菜单序号（1 起，与展示顺序一致）。 */
    int order();

    /** 菜单标题（一句话说明本示例教什么）。 */
    String title();

    /** 运行示例。进入 TUI 模式的示例在返回前须恢复终端。 */
    void run() throws Exception;
}

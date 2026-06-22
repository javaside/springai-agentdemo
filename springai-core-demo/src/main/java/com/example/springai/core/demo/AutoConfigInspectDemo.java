package com.example.springai.core.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 示例 0：自动配置揭秘 —— “这些 Bean 到底是谁、从哪来的？”
 *
 * <p>很多新手最大的困惑是：像 {@code EmbeddingModel}、{@code ChatModel} 这些对象，
 * 代码里既看不到 {@code new}、也看不到 {@code @Bean}，怎么就能直接注入使用了？
 *
 * <p>答案是 Spring Boot 的核心机制 ——「自动配置（auto-configuration）」：
 * 启动时它会根据 classpath 上引入了哪些 starter，自动帮你创建并注册一批 Bean。
 * 本示例不调用任何模型（零成本、可放心第一个运行），它把容器里这些 AI 相关 Bean
 * 的「实现类 / 来自哪个 jar / 是自动配置还是我们手写」全部打印出来，让“看不见的”变“看得见”。
 */
@Component
public class AutoConfigInspectDemo implements Demo {

    private final ApplicationContext ctx;

    public AutoConfigInspectDemo(ApplicationContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public String title() {
        return "自动配置揭秘（这些 Bean 是谁、从哪来）★建议先看";
    }

    @Override
    public int order() {
        return 0; // 排在菜单最前
    }

    @Override
    public void run() {
        System.out.println("""
                「自动配置(auto-configuration)」是 Spring Boot 最核心的机制：
                启动时它会根据 classpath 上有哪些 starter，自动创建并注册一批 Bean；
                你无需写 @Bean，在构造器里声明类型就能注入使用。
                这就是为什么 EmbeddingModel 在代码里找不到 new、也找不到 @Bean —— 它是被“自动配置”出来的。
                下面把本应用里这些 AI 相关 Bean 摊开看：
                """);

        // EmbeddingModel：本地向量模型，由 transformers starter 自动配置（就是之前让人困惑的那个）
        inspect("EmbeddingModel", EmbeddingModel.class,
                "starter 自动配置（spring-ai-starter-model-transformers）");

        // ChatModel：底层对话模型，由 deepseek starter 自动配置
        inspect("ChatModel", ChatModel.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");

        // ChatClient.Builder：构建 ChatClient 的工厂，也由 starter 自动配置
        inspect("ChatClient.Builder", ChatClient.Builder.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");

        // ChatClient：唯一一个“我们自己手写 @Bean”的，对比着看最能体会区别
        inspect("ChatClient", ChatClient.class,
                "我们手动 @Bean（见 config/ChatClientConfig，为了加默认系统提示词）");

        System.out.println("结论：除了 ChatClient 是我们手写的，其余都是 starter 自动配置的。");
        System.out.println("想自定义自动配置的 Bean？在 application.properties 里改对应的 spring.ai.* 配置即可。");
    }

    /** 从容器取出某类型的 Bean，打印它的真实实现类、所在 jar、以及配置方式。 */
    private void inspect(String typeName, Class<?> beanType, String how) {
        try {
            Object bean = ctx.getBean(beanType);
            System.out.printf("• %s%n", typeName);
            System.out.printf("    实现类  = %s%n", bean.getClass().getName());
            System.out.printf("    来自 jar = %s%n", jarOf(bean.getClass()));
            System.out.printf("    配置方式 = %s%n%n", how);
        } catch (Exception e) {
            System.out.printf("• %s （未找到：%s）%n%n", typeName, e.getMessage());
        }
    }

    /**
     * 取得某个类所在的 jar 文件名，让“来自哪个依赖”一目了然。
     * 兼容两种运行方式：mvn spring-boot:run（依赖是 .m2 里的独立 jar）
     * 与 java -jar 可执行包（依赖是嵌套在 BOOT-INF/lib 里的 jar）。
     */
    private static String jarOf(Class<?> clazz) {
        try {
            var codeSource = clazz.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "(JDK 内置)";
            }
            String url = codeSource.getLocation().toString();
            // 从 URL 中提取最后一个 *.jar 片段（嵌套 jar 的 URL 里可能含多个）
            var matcher = java.util.regex.Pattern.compile("([^/!]+\\.jar)").matcher(url);
            String last = null;
            while (matcher.find()) {
                last = matcher.group(1);
            }
            return last != null ? last : url;
        } catch (Exception e) {
            return "(未知)";
        }
    }
}

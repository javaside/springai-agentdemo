package com.example.springai.boot.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 示例：自动配置揭秘 —— “这些 Bean 是谁、从哪来的？”
 *
 * <p>这是本模块的核心看点。core/agent 里你亲手 new 出来的 ChatModel、EmbeddingModel、
 * ChatClient.Builder，在这里【一行 new 都没有】，却能直接注入使用。本示例零成本（不调模型），
 * 把容器里这些 AI Bean 的实现类 / 来自哪个 jar / 配置方式打印出来，证明它们是“自动配置”出来的。
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
        return 0;
    }

    @Override
    public void run() {
        System.out.println("""
                「自动配置(auto-configuration)」是 Spring Boot 最核心的机制：
                启动时它会根据 classpath 上有哪些 starter，自动创建并注册一批 Bean；
                你无需写 @Bean / new，在构造器里声明类型就能注入使用。
                下面这些 Bean 在 core/agent 里都要手动 new，这里却全是自动配置出来的：
                """);

        inspect("EmbeddingModel", EmbeddingModel.class,
                "starter 自动配置（spring-ai-starter-model-transformers）");
        inspect("ChatModel", ChatModel.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");
        inspect("ChatClient.Builder", ChatClient.Builder.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");
        inspect("ChatClient", ChatClient.class,
                "我们手动 @Bean（见 config/ChatClientConfig，为了加默认系统提示词）");

        System.out.println("结论：除了 ChatClient 是我们手写的，其余都是 starter 自动配置的。");
        System.out.println("想自定义自动配置的 Bean？在 application.properties 改对应的 spring.ai.* 配置即可。");
    }

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

    /** 取得某个类所在的 jar 文件名，兼容 mvn spring-boot:run 与 java -jar 两种运行方式。 */
    private static String jarOf(Class<?> clazz) {
        try {
            var codeSource = clazz.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                return "(JDK 内置)";
            }
            String url = codeSource.getLocation().toString();
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

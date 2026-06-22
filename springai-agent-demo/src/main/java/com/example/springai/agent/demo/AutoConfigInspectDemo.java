package com.example.springai.agent.demo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 示例 0：自动配置揭秘 —— “这些 Bean 到底是谁、从哪来的？”
 *
 * <p>新手常困惑：{@code ChatModel}、{@code ChatClient.Builder} 这些对象，代码里既没 {@code new}
 * 也没 {@code @Bean}，为何能直接注入？这是 Spring Boot 的「自动配置（auto-configuration）」：
 * 启动时根据 classpath 上的 starter 自动创建并注册 Bean。
 *
 * <p>本示例零成本（不调模型），把容器里 AI 相关 Bean 的实现类、所在 jar、配置方式打印出来。
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
                你无需写 @Bean，在构造器里声明类型就能注入使用。
                下面把本应用里这些 AI 相关 Bean 摊开看：
                """);

        inspect("ChatModel", ChatModel.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");
        inspect("ChatClient.Builder", ChatClient.Builder.class,
                "starter 自动配置（spring-ai-starter-model-deepseek）");
        inspect("ChatClient", ChatClient.class,
                "我们手动 @Bean（见 config/ChatClientConfig）");

        System.out.println("提示：而本模块里的「工具对象」(DateTimeTools 等) 和「记忆」(ChatMemory)，");
        System.out.println("是我们在示例代码里用 new / builder 显式创建的 —— 这类你能直接看到，不是自动配置。");
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

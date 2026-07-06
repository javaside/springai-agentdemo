package io.github.javaside.springai.codetui.agent;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 加载 spring-ai-agent-utils jar 内的长期记忆系统提示，并注入记忆根目录路径。
 *
 * <p><b>为何不走 ST/PromptTemplate 渲染</b>：该 prompt 文件同时含单花括号占位符
 * {@code {MEMORIES_ROOT_DIERCTORY}}（库拼写如此）与双花括号示例块 {@code {{memory name}}}。
 * 若交给 Spring AI 的 ST 模板引擎解析，花括号会被当模板语法直接抛异常。故这里用纯
 * {@link String#replace} 注入路径，得到的整段字符串再作为<b>一个 param 的值</b>注入
 * 主系统模板（ST 不递归解析注入值），使示例里的 {@code {{...}}} 安全存活。
 *
 * <p>库的占位符键名 {@code MEMORIES_ROOT_DIERCTORY} 为上游拼写（DIRECTORY 拼错），
 * 此处必须照抄，否则替换不中。
 */
public final class MemoryPrompt {

    /** jar 内资源路径（org.springaicommunity:spring-ai-agent-utils）。 */
    private static final String RESOURCE = "prompt/AUTO_MEMORY_TOOLS_SYSTEM_PROMPT.md";

    /** 上游占位符（拼写照抄，含 DIERCTORY 笔误）。 */
    private static final String ROOT_PLACEHOLDER = "{MEMORIES_ROOT_DIERCTORY}";

    private MemoryPrompt() {
    }

    /**
     * 读取记忆系统提示并把根目录占位符替换为 {@code memoriesDir}。
     *
     * @param memoriesDir 记忆根目录的绝对路径字符串（如 {@code <root>/.codetui/memory}）
     * @return 渲染好的整段提示；供作为主系统模板的一个 param 值注入
     * @throws NullPointerException  当 {@code memoriesDir} 为 null
     * @throws IllegalStateException 当上游 prompt 已无该占位符（更名/修正拼写）——把静默漏注入变成显式失败
     */
    public static String render(String memoriesDir) {
        Objects.requireNonNull(memoriesDir, "memoriesDir");
        String raw = load();
        // String.replace 找不到占位符会原样返回，导致「无路径注入」却无任何报错。
        // 一旦上游修正 DIERCTORY 拼写或更名 key，这里必须炸响，而非让 agent 带着坏指令运行。
        if (!raw.contains(ROOT_PLACEHOLDER)) {
            throw new IllegalStateException(
                    "记忆提示中未找到占位符 " + ROOT_PLACEHOLDER
                            + "，spring-ai-agent-utils 可能已更名或修正拼写（检查上游版本）");
        }
        return raw.replace(ROOT_PLACEHOLDER, memoriesDir);
    }

    private static String load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        try (var in = res.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "无法加载记忆系统提示资源：" + RESOURCE + "（检查 spring-ai-agent-utils 依赖）", e);
        }
    }
}

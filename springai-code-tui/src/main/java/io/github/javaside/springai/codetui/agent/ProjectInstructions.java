package io.github.javaside.springai.codetui.agent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 加载人手写的项目指令文件 {@code AGENTS.md}（CLAUDE.md / AGENTS.md 生态标准），注入 agent 系统提示。
 *
 * <p><b>两层</b>（load 顺序 broad→specific）：用户级 {@code ~/.codetui/AGENTS.md}（跨项目个人约定）→
 * 项目级 {@code <root>/AGENTS.md}（团队共享、提交入库）。项目级后读、优先级更高。任一缺失/空白则跳过；
 * 两层皆无返回 {@code ""}（注入段为空、无操作——绝大多数项目初始没有 AGENTS.md）。
 *
 * <p><b>只读、不渲染</b>：本类只把文件内容原样拼接并加来源标签，<b>不</b>做任何模板替换。AGENTS.md 常含
 * {@code {...}} 代码片段/占位符；调用方（{@code AgentTools}）把整段作为一个 <b>param 值</b>注入主系统模板，
 * Spring AI 的 ST 引擎不递归解析注入值，故花括号安全（与 {@code AUTO_MEMORY} 同构）。子 agent 侧走纯字符串
 * 追加，同样不经 ST。
 *
 * <p>这是<b>人手写、提交入库、只读注入</b>的「instructions」，与 {@code AutoMemoryTools}（agent 自写、
 * 机器本地的「auto memory」）正交互补。
 */
public final class ProjectInstructions {

    /** 项目级文件名（生态标准，位于项目根）。 */
    static final String PROJECT_FILE = "AGENTS.md";

    /** 用户级相对 home 的路径（与 Skills 用户层同处 ~/.codetui 下）。 */
    static final String USER_RELATIVE = ".codetui/AGENTS.md";

    private ProjectInstructions() {
    }

    /**
     * 读取用户级 + 项目级 {@code AGENTS.md} 并拼成一段可注入的指令文本。
     *
     * @param root 项目根目录（项目级文件位于 {@code root/AGENTS.md}）
     * @return 拼好的指令段；两层皆缺/空白时为 {@code ""}
     */
    public static String load(Path root) {
        StringBuilder body = new StringBuilder();
        appendIfPresent(body, userAgentsPath(), "user_instructions", "个人级（跨项目，来自 ~/.codetui/AGENTS.md）");
        appendIfPresent(body, root.resolve(PROJECT_FILE), "project_instructions", "项目级（本仓库，来自 AGENTS.md）");
        if (body.length() == 0) {
            return "";
        }
        return "以下是你需要遵循的项目 / 个人指令（AGENTS.md）。请当作项目约定认真遵守；"
                + "两段冲突时以更具体的「项目级」为准；用户在当前对话中的明确要求高于一切。\n\n"
                + body;
    }

    /** 用户级 AGENTS.md 的绝对路径（每次读实时取 user.home，便于测试用 fakeHome 隔离）。 */
    private static Path userAgentsPath() {
        return Path.of(System.getProperty("user.home")).resolve(USER_RELATIVE);
    }

    /** 若文件存在且非空白，读出并以 {@code <tag>} 包裹追加（带来源说明行）。 */
    private static void appendIfPresent(StringBuilder body, Path file, String tag, String sourceLabel) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取项目指令文件失败：" + file, e);
        }
        if (content.isBlank()) {
            return;
        }
        body.append('<').append(tag).append(" source=\"").append(sourceLabel).append("\">\n")
                .append(content.strip()).append('\n')
                .append("</").append(tag).append(">\n\n");
    }
}

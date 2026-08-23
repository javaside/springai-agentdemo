package io.github.javaside.springai.codetui.agent;

import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 工具执行异常处理器：把工具抛出的任何异常转成一条<b>信息完整的错误文本</b>回给模型，让回合继续，
 * <b>绝不</b> rethrow 终止。
 *
 * <p><b>为什么需要它</b>：Spring AI 默认的 {@code DefaultToolExecutionExceptionProcessor}
 * 对部分异常（Error 类 cause、或配置为 rethrow 的类型）会直接抛出，异常沿 advisor 链上传，
 * {@code CodingAgent.handleError} 把整回合标成失败——一次工具失败（如类加载错乱、某次调用
 * 内部异常）就毁掉整个回合。而把错误文本回给模型（与正常工具结果同路径）是 agent 的标准
 * 做法：模型看到错误后会自行调整（换个工具、换个参数、或向用户说明），回合不中断。
 *
 * <p><b>为什么错误信息要完整</b>：模型看不到终端堆栈，只有这条 tool 结果。若只给一句
 * 「工具 X 执行出错」，模型无从判断原因（是类加载失败？文件不存在？参数非法？），只能盲试。
 * 故这里给出：异常类型、消息、cause 链，以及<b>应用层堆栈的前若干帧</b>——既让模型能对症
 * 调整，又避免把整段几百行堆栈塞进上下文（token 浪费）。完整堆栈仍在日志里。
 * <b>不附加任何引导词</b>：错误原因本身就是信息，模型自会判断下一步。
 *
 * <p><b>实现</b>：{@link #process} 拼出「工具 X 执行出错：<类型>: <消息>」+ cause 链逐层 +
 * 「堆栈：<前 N 帧>」。N 取 6，足够定位到业务代码（如 {@code CodingAgent.handleChunk}、
 * {@code DefaultToolCallingManager.executeToolCall}），又不过长。
 */
public final class ResilientToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    /** 回给模型的堆栈帧数：够定位业务层原因，又不过长。 */
    private static final int STACK_FRAMES = 6;

    @Override
    public String process(ToolExecutionException exception) {
        String toolName = exception.getToolDefinition() == null
                || exception.getToolDefinition().name() == null
                ? "未知工具"
                : exception.getToolDefinition().name();
        StringBuilder sb = new StringBuilder();
        sb.append("工具 ").append(toolName).append(" 执行出错");

        // cause 链：从最外层（ToolExecutionException 自身）逐层往内，全部列出——
        // 每层都是成因的一部分（如 ToolExecutionException → IllegalStateException → NoClassDefFoundError）。
        // 单独成方法便于读；这里先收集成链（去重防自引用）。
        java.util.List<Throwable> chain = new java.util.ArrayList<>();
        for (Throwable t = exception; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        // 真正的原因是最内层（链尾），优先呈现；外层包裹异常其次。
        Throwable root = chain.get(chain.size() - 1);
        appendThrowable(sb, root);
        for (int i = chain.size() - 2; i >= 0; i--) {
            sb.append("；引发于");
            appendThrowable(sb, chain.get(i));
        }

        // 关键堆栈帧（从根异常的栈里取，最贴近真实出错点）。
        StackTraceElement[] frames = root.getStackTrace();
        if (frames != null && frames.length > 0) {
            sb.append("。堆栈：");
            int n = Math.min(STACK_FRAMES, frames.length);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(" ← ");
                sb.append(shortFrame(frames[i]));
            }
        }
        return sb.toString();
    }

    private static void appendThrowable(StringBuilder sb, Throwable t) {
        sb.append("：").append(t.getClass().getSimpleName());
        String msg = t.getMessage();
        if (msg != null && !msg.isBlank()) {
            sb.append(": ").append(msg);
        }
    }

    /** 压缩堆栈帧：只留 类.方法(文件:行)，去掉包名前缀（太长且模型用不上）。 */
    private static String shortFrame(StackTraceElement f) {
        String cls = f.getClassName();
        int dot = cls.lastIndexOf('.');
        String simple = dot >= 0 ? cls.substring(dot + 1) : cls;
        return simple + "." + f.getMethodName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
    }
}

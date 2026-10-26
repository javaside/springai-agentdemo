package io.github.javaside.springai.codetui.agent.hooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 钩子进程执行：{@code sh -c command}、cwd=root、stdin 喂 JSON payload。
 *
 * <p><b>读线程的必要性</b>：若先 waitFor 再读输出，钩子写满管道缓冲（通常 64KB）后会阻塞在
 * write 上、永远退不出——prettier 这类多输出钩子会把自身卡到超时被杀。故先起两个守护读线程
 * 有界吸走输出，再等进程。
 *
 * <p><b>永不抛</b>：启动失败/IO 异常一律 {@code HookResult(-1,…)}——钩子坏不该打断工具调用
 * （工具容错白名单的老账）。
 */
public final class HookExecutor {

    private static final Logger log = LoggerFactory.getLogger(HookExecutor.class);
    private static final int OUTPUT_CAP = 64 * 1024;   // 每流捕获上限：payload 回传另有 2000 字截断

    public record HookResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

    private HookExecutor() {}

    public static HookResult execute(HookDefinition def, String payloadJson, Path root) {
        Process p;
        try {
            p = new ProcessBuilder("sh", "-c", def.command())
                    .directory(root.toFile())
                    .start();
        } catch (IOException e) {
            log.warn("钩子进程启动失败（{}）：{}", def.command(), e.toString());
            return new HookResult(-1, "", "启动失败：" + e, false);
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = drain(p.getInputStream(), out);
        Thread tErr = drain(p.getErrorStream(), err);
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(payloadJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("写钩子 stdin 失败（钩子可能已提前退出）：{}", e.toString());
        }
        boolean finished;
        try {
            finished = p.waitFor(def.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            return new HookResult(-1, out.toString(), "等待被中断", false);
        }
        if (!finished) {
            p.destroyForcibly();
            return new HookResult(-1, out.toString(), "超时（>" + def.timeoutSeconds() + "s）", true);
        }
        joinQuietly(tOut);
        joinQuietly(tErr);
        return new HookResult(p.exitValue(), out.toString(), err.toString(), false);
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (in) {
                byte[] buf = new byte[4096];
                int n;
                while (sb.length() < OUTPUT_CAP && (n = in.read(buf)) >= 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程退出后 read 抛 EOF/Broken pipe 属正常收尾
            }
        }, "hook-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(1000);   // 有界 join：读线程被喂满 64KB 后自会退，1s 兜底防挂测试
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package dev.tamboui.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * 结构保护（同 code-tui {@code ScreenCleanerTest} 纪律）：钉死 pty 错误探针依赖的
 * 上游私有字段仍存在——库升级改名/改型在此红灯，提示回去更新反射路径（而非线上静默降级）。
 *
 * <p><b>被钉的反射链</b>：{@code InlineTuiRunner.errorProbeFor} 经
 * {@code backend.getClass().getDeclaredField("writer")} 取 {@code JLineBackend} 的
 * 私有 {@code PrintWriter}，探针 {@code checkError()} 交给 {@code AsyncPtyWriter}。
 * 背景（审核 M-2）：JLine 后端的 {@code writeRaw} 走 PrintWriter——<b>吞掉 IOException
 * 只置 checkError 标志</b>，没有探针时写线程永远等不到异常，死终端（tab 被关、
 * ssh 断开）静默流失全部内容。
 *
 * <p><b>为什么钉类型不只是钉存在</b>：{@code errorProbeFor} 里是
 * {@code writer instanceof PrintWriter} 判定——字段若被上游改成 {@code Writer} /
 * {@code OutputStream}，字段仍在但探针静默返回 null（降级回只靠 IOException 的旧行为），
 * 恰是本钉要拦的漂移。
 */
class InlineTuiRunnerErrorProbeTest {

    @Test
    void jlineBackendWriterField_stillExistsAsPrintWriter() throws Exception {
        Class<?> backend = Class.forName("dev.tamboui.backend.jline3.JLineBackend");
        java.lang.reflect.Field writer = backend.getDeclaredField("writer");
        assertNotNull(writer, "JLineBackend.writer 是错误探针的反射目标，改名在此红灯");
        assertEquals(java.io.PrintWriter.class, writer.getType(),
                "writer 必须声明为 PrintWriter：errorProbeFor 的 instanceof 判定不认父类型，"
                        + "改成 Writer/OutputStream 会让探针静默失效（死终端流失内容）");
    }
}

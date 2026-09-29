package io.github.javaside.springai.codetui.agent.goal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /goal} 自主循环的 PTY 实机冒烟启动器：真起一个 PTY 里的 Code TUI，跑一轮
 * UNSAT→SAT 闭环（脚本内全桩模型：主链 SSE 桩 + 评估器非流式 JSON 桩，无 key、无外网）。
 *
 * <p><b>这是 goal 接线唯一的端到端网</b>（脚本头注列了四类单测到不了的东西：wireGoal 接线、
 * 评估器 requestSelection 路由、C1 落库存活、真终端状态栏）。断言主体全在
 * {@code src/test/resources/scripts/goal_smoke.py} 里——真凭据是<b>桩收到的请求体</b>
 * （主链 ≥3 次对话请求、第 2/3 次的 user 文本带 {@code [goal 继续 N/} 前缀与评估结论行；
 * 评估器 ≥2 次且含「目标条件：/轮次：」要素），外加屏幕的 {@code ◎ goal} 指示与
 * {@code ◎ goal 终态：SATISFIED} 一行式总结、会话文件里的 {@code [goal 评估]} 标记事件
 * （非尾、未被 fold 混入/销毁）。
 *
 * <p><b>门控</b>：默认不跑（PTY + 墙钟断言 + 需要 package 产物，CI/无终端环境会假失败），
 * 绑 {@code CODETUI_SMOKE_TESTS=1} 显式开启（门控模式同 {@code BochaWebSearchSmokeTest} 的
 * {@code CODETUI_LIVE_TESTS} 先例；本冒烟不需要真实 key 与网络）。启用前提（照脚本头注）：
 *
 * <pre>{@code
 * mvn -q -DskipTests install                       # 全项目装本地仓库（build-classpath 不走 reactor）
 * mvn -q -pl springai-code-tui package -DskipTests
 * mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
 * CODETUI_SMOKE_TESTS=1 mvn -pl springai-code-tui test -Dtest=GoalSmokeTest
 * }</pre>
 *
 * <p>等价的手工跑法（与既有 PTY 冒烟一致）：{@code /usr/bin/python3
 * springai-code-tui/src/test/resources/scripts/goal_smoke.py}。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_SMOKE_TESTS", matches = "1")   // 默认跳过：PTY+慢+需 package 产物
class GoalSmokeTest {

    /** 仓库内模块根（surefire 的 cwd = 模块 basedir；从 test 类定位以防万一）。 */
    private static Path moduleRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("src/test/resources/scripts"))) {
            dir = dir.getParent();
        }
        assertTrue(dir != null, "找不到模块根（src/test/resources/scripts）：user.dir="
                + System.getProperty("user.dir"));
        return dir;
    }

    @Test
    @Timeout(value = 360, unit = TimeUnit.SECONDS)
    void goalUnsatToSatLoopPassesPtySmoke() throws IOException, InterruptedException {
        Path script = moduleRoot().resolve("src/test/resources/scripts/goal_smoke.py");
        assertTrue(Files.isRegularFile(script), "冒烟脚本缺失: " + script);
        assertTrue(Files.isRegularFile(moduleRoot().resolve("target/cp.txt")),
                "target/cp.txt 缺失——先跑 dependency:build-classpath（见类 javadoc 前置命令）");

        // 输出自己收拢再经 System.out 打印：Redirect.INHERIT 直写原生流会打穿 surefire 的
        // fork 通道（"Corrupted channel" 告警），且收拢后才能断言 SMOKE PASS 标记本身。
        Process p = new ProcessBuilder("/usr/bin/python3", script.toString())
                .redirectErrorStream(true)
                .start();
        StringBuilder out = new StringBuilder();
        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        boolean finished = p.waitFor(350, TimeUnit.SECONDS);
        System.out.println("[goal-smoke] 脚本输出：\n" + out);
        assertTrue(finished, "冒烟脚本 350s 内没跑完（PTY 挂死？）——上方为已收集输出");
        assertEquals(0, p.exitValue(), "goal PTY 冒烟红了（exit=" + p.exitValue()
                + "）——上方输出含 SMOKE FAIL 原因与最后一屏");
        assertTrue(out.toString().contains("SMOKE PASS"),
                "exit 0 但没有 SMOKE PASS 标记——脚本输出异常");
    }
}

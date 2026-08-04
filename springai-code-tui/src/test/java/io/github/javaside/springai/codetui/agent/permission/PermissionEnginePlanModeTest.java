package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN 模式：只读放行，其余一律 <b>DENY 而非 ASK</b>。
 *
 * <p>为什么是 DENY：能当场批准就等于没有计划模式。DENY 串还要引导模型转向
 * {@code ExitPlanMode}，否则它只会反复重试同一个写操作、把回合耗光。
 */
class PermissionEnginePlanModeTest {

    private static PermissionEngine plan(Path root, PermissionRule... rules) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of(rules)),
                PermissionMode.PLAN);
    }

    @Test
    @DisplayName("只读工具照常放行（计划模式要能调查）")
    void readOnlyStillAllowed(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Read", "{\"filePath\":\"" + root.resolve("a.txt") + "\"}").behavior());
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Grep", "{\"path\":\"" + root + "\"}").behavior());
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Bash", "{\"command\":\"git status\"}").behavior(),
                "只读命令白名单在 PLAN 下同样放行");
    }

    @Test
    @DisplayName("写文件 / 非只读命令 / 未登记工具一律 DENY，且原因引导模型去 ExitPlanMode")
    void mutationsDenied(@TempDir Path root) {
        PermissionEngine e = plan(root);

        PermissionDecision write = e.decide("Write", "{\"filePath\":\"" + root.resolve("a.txt") + "\"}");
        assertEquals(PermissionBehavior.DENY, write.behavior(), "PLAN 下写文件必须 DENY，不是 ASK");
        assertTrue(write.reason().contains("ExitPlanMode"),
                "拒绝串必须指路，否则模型只会反复重试同一个写操作：" + write.reason());

        assertEquals(PermissionBehavior.DENY,
                e.decide("Bash", "{\"command\":\"mvn test\"}").behavior());
        assertEquals(PermissionBehavior.DENY,
                e.decide("mcp__x__do_thing", "{\"any\":\"payload\"}").behavior(),
                "未登记工具（含全部 MCP）在 PLAN 下也不许动手");
    }

    @Test
    @DisplayName("内部工具放行——否则 ExitPlanMode 自己就被拦住了，计划模式成了死胡同")
    void internalToolsAllowed(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ALLOW, e.decide("TodoWrite", "{}").behavior());
        assertEquals(PermissionBehavior.ALLOW, e.decide("AskUserQuestionTool", "{}").behavior());
    }

    @Test
    @DisplayName("网络工具在 PLAN 下仍是 ASK——调查要查资料，但请求内容会离开本机")
    void networkStillAsks(@TempDir Path root) {
        PermissionEngine e = plan(root);
        assertEquals(PermissionBehavior.ASK,
                e.decide("WebFetch", "{\"url\":\"https://docs.spring.io/x\"}").behavior());
    }

    @Test
    @DisplayName("PLAN 下危险操作是 DENY 不是 ASK——否则只有最危险的那批还能被当场批准")
    void dangerousOpsDeniedNotAsked(@TempDir Path root) {
        PermissionEngine e = plan(root);

        PermissionDecision rm = e.decide("Bash", "{\"command\":\"rm -rf ~\"}");
        assertEquals(PermissionBehavior.DENY, rm.behavior(),
                "PLAN 下 rm -rf ~ 若是 ASK，用户就能当场批准它——而普通的 mvn test 反而被拒，结论是倒置的");
        assertTrue(rm.reason().contains("ExitPlanMode"), "仍要指路");

        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", "{\"filePath\":\"" + System.getProperty("user.home") + "/.ssh/authorized_keys\"}")
                        .behavior());
    }

    @Test
    @DisplayName("但读密钥在 PLAN 下仍是 ASK——PLAN 承诺的是「不动手」，不是「不读」")
    void readingSecretsStillAsks(@TempDir Path root) {
        assertEquals(PermissionBehavior.ASK,
                plan(root).decide("Read", "{\"filePath\":\"" + System.getProperty("user.home") + "/.ssh/id_rsa\"}")
                        .behavior());
    }

    @Test
    @DisplayName("deny 规则仍排第一；allow 规则仍排在模式默认之前（既定语义，别当 bug 修）")
    void ruleOrderUnchanged(@TempDir Path root) {
        PermissionEngine denied = plan(root,
                PermissionRule.parse("Read(**/secret.txt)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                denied.decide("Read", "{\"filePath\":\"" + root.resolve("secret.txt") + "\"}").behavior(),
                "deny 排第 1 步，PLAN 不改变它");

        PermissionEngine allowed = plan(root,
                PermissionRule.parse("Write(**/notes.md)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ALLOW,
                allowed.decide("Write", "{\"filePath\":\"" + root.resolve("notes.md") + "\"}").behavior(),
                "allow 规则排在模式默认之前，故 PLAN 下也放行——这是决策顺序的既定语义");
    }
}

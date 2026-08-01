package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigWriterRemoveTest {

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.PROJECT);
    }

    private static PermissionRule deny(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.DENY, RuleScope.PROJECT);
    }

    /** 取出某个数组字段的确切内容与顺序——只断言 contains 的话，顺序错乱与误删同伴都发现不了。 */
    private static List<String> entries(Path file, String key) throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(file));
        JsonNode arr = root.get(key);
        if (arr == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.stringValue()));
        return out;
    }

    @Test
    @DisplayName("只删指定那一条，其余条目与顺序不动")
    void removesOnlyTheTargetKeepingOrder(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"allow":["Read(*)","Write(src/**)","Bash(ls:*)"]}""");

        assertTrue(PermissionConfigWriter.remove(file, allow("Write(src/**)")));

        assertEquals(List.of("Read(*)", "Bash(ls:*)"), entries(file, "allow"));
    }

    @Test
    @DisplayName("只动本行为对应的数组——同一条 DSL 也可能同时写在 deny 里，删 allow 不该碰它")
    void removesOnlyFromMatchingBehaviorArray(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"allow":["Read(*)"],"deny":["Read(*)"]}""");

        assertTrue(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(List.of(), entries(file, "allow"));
        assertEquals(List.of("Read(*)"), entries(file, "deny"), "deny 里的同名规则必须还在");
    }

    @Test
    @DisplayName("未知字段与其它字段原样保留——那是用户手写的内容")
    void preservesUnknownFields(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"defaultMode":"ACCEPT_EDITS",
                 "someFutureField":{"keep":true},
                 "allow":["Read(*)","Write(src/**)"],
                 "deny":["Bash(rm -rf /:*)"]}""");

        assertTrue(PermissionConfigWriter.remove(file, allow("Write(src/**)")));

        String json = Files.readString(file);
        assertTrue(json.contains("someFutureField"), "未知字段必须原样保留：" + json);
        assertTrue(json.contains("keep"), "未知字段的子结构也要保留：" + json);
        assertTrue(json.contains("ACCEPT_EDITS"), "实际内容：" + json);
        assertEquals(List.of("Read(*)"), entries(file, "allow"));
        assertEquals(List.of("Bash(rm -rf /:*)"), entries(file, "deny"));
    }

    /**
     * 重复项<b>相邻</b>是关键：正着扫边删边前移，后一条会补到已扫过的下标上被跳过。
     * 若只放不相邻的重复项，正着删也能全删掉——这条用例就抓不到它本该抓的 bug。
     * 故两种排布都放进来。
     */
    @Test
    @DisplayName("同一条 DSL 手工写重复了——相邻的、隔开的，都要删干净")
    void removesAllDuplicateOccurrences(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, """
                {"allow":["Read(*)","Read(*)","Write(src/**)","Read(*)"]}""");

        assertTrue(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(List.of("Write(src/**)"), entries(file, "allow"));
    }

    @Test
    @DisplayName("规则不在文件里 → true 且一个字节都不写（幂等：无谓重写白改 mtime，也多一次写坏的机会）")
    void absentRuleIsNoOpAndLeavesFileByteIdentical(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = """
                {"allow":["Read(*)"],"someFutureField":1}""";
        Files.writeString(file, before);

        assertTrue(PermissionConfigWriter.remove(file, allow("Write(src/**)")));

        assertEquals(before, Files.readString(file), "不该重写：内容必须逐字节相同");
    }

    @Test
    @DisplayName("对应数组根本不存在 → true 且不写盘")
    void missingArrayIsNoOp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = """
                {"deny":["Bash(rm:*)"]}""";
        Files.writeString(file, before);

        assertTrue(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(before, Files.readString(file), "没有可删的东西时不该重写");
    }

    @Test
    @DisplayName("对应字段被手写成了非数组 → true 且不写盘（加载侧本就忽略该字段，规则并未生效）")
    void nonArrayFieldIsNoOp(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = """
                {"allow":"Read(*)"}""";
        Files.writeString(file, before);

        assertTrue(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(before, Files.readString(file), "不是数组就别去动它——那是用户手写的内容");
    }

    @Test
    @DisplayName("JSON 非法 → false 且文件原样（绝不能把用户手写的规则抹掉）")
    void invalidJsonLeavesFileUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = "{\"allow\":[\"Read(*)\"],";      // 缺右括号
        Files.writeString(file, before);

        assertFalse(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(before, Files.readString(file), "非法 JSON 必须原样留着");
    }

    @Test
    @DisplayName("重复键必须与读侧同判非法——否则末键胜出会把被覆盖的 deny 真正抹掉")
    void duplicateKeysAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = """
                {"deny":["Bash(rm -rf /:*)"],"allow":["Read(*)"],"deny":[]}""";
        Files.writeString(file, before);

        assertFalse(PermissionConfigWriter.remove(file, allow("Read(*)")),
                "重复键文件必须拒绝，不能读成末键胜出的树再重写回去");
        assertEquals(before, Files.readString(file), "那条被覆盖的 deny 必须还在文件里");
    }

    @Test
    @DisplayName("顶层不是 JSON 对象 → false 且文件原样")
    void nonObjectRootLeavesFileUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        String before = "[\"Read(*)\"]";
        Files.writeString(file, before);

        assertFalse(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertEquals(before, Files.readString(file));
    }

    @Test
    @DisplayName("文件不存在 → false，且绝不顺手建一个空文件出来")
    void missingFileReturnsFalseAndIsNotCreated(@TempDir Path dir) {
        Path file = dir.resolve(".codetui").resolve("permissions.json");

        assertFalse(PermissionConfigWriter.remove(file, allow("Read(*)")));

        assertFalse(Files.exists(file), "不该被创建出来：" + file);
        assertFalse(Files.exists(dir.resolve(".codetui")), "连目录也不该建");
    }

    @Test
    @DisplayName("null 入参不抛——调用方在 UI 回调里，不该为此加判空")
    void nullArgumentsAreSafe(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        Files.writeString(file, "{}");

        assertFalse(PermissionConfigWriter.remove(null, allow("Read(*)")));
        assertFalse(PermissionConfigWriter.remove(file, null));
    }

    @Test
    @DisplayName("删掉 append 刚写进去的那条——两边认的是同一个 DSL 字符串")
    void removesWhatAppendWrote(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("permissions.json");
        assertTrue(PermissionConfigWriter.append(file, deny("Bash(curl:*)")));
        assertEquals(List.of("Bash(curl:*)"), entries(file, "deny"));

        assertTrue(PermissionConfigWriter.remove(file, deny("Bash(curl:*)")));

        assertEquals(List.of(), entries(file, "deny"));
    }
}

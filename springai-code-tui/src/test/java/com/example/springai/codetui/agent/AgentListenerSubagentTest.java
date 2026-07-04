package com.example.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 新增的 4 参 onSubagentFinished（带 ok）默认委托回旧 3 参，保证既有实现零改动。 */
class AgentListenerSubagentTest {

    @Test
    void finishedFourArg_delegatesToThreeArg() {
        List<String> got = new ArrayList<>();
        AgentListener l = new StubListener() {
            @Override public void onSubagentFinished(long turnId, String taskId, String finalText) {
                got.add(finalText);
            }
        };
        l.onSubagentFinished(1L, "t1", "hello", true);    // 默认 4 参 → 3 参
        l.onSubagentFinished(1L, "t1", "world", false);
        assertEquals(List.of("hello", "world"), got, "4 参默认实现应把 finalText 转交 3 参");
    }
}

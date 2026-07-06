package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话 id：文件名安全、每次不同。 */
class SessionIdsTest {

    @Test
    void newId_isFilenameSafe_andUnique() {
        String a = SessionIds.newId();
        String b = SessionIds.newId();
        assertTrue(a.matches("[0-9A-Za-z-]+"), "只含文件名安全字符：" + a);
        assertNotEquals(a, b, "两次生成应不同");
    }
}

package io.github.javaside.springai.codetui.agent.session;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 新会话 id 生成：UTC 时间戳 + 短随机，文件名安全（[0-9A-Za-z-]），可按名近似排序。 */
public final class SessionIds {

    private SessionIds() {
    }

    public static String newId() {
        String ts = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return ts + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}

package io.github.javaside.springai.codetui.agent.llm;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;

/** 测试 fixture：构造智谱形态的 429（业务码 + 中文 message 内嵌重置时刻）。 */
public final class Quota429s {
    private Quota429s() { }

    public static RateLimitException quota429(String code, String message) {
        // 4.49.0 的 ErrorObject.Builder.build() 对 code/message/param/type 全部 checkRequired
        // （setter 调用过即算已设置；ofNullable(null) 落 JsonNull 可通过校验）——param/type 必须显式
        // 调用 setter 才不抛 IllegalStateException。裸 null 在 param 三个重载间有歧义，需强转 String。
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder()
                        .code(code)
                        .message(message)
                        .param((String) null)
                        .type("")
                        .build())
                .build();
    }
}

package io.github.javaside.springai.codetui.agent.media;

/** 判定一段 String 是否疑似二进制（工具把二进制文件解码成文本后的特征）：
 *  含 NUL、或替换符/控制字符占比过高。只看前若干字符，成本恒定。 */
public final class BinarySniff {
    private BinarySniff() {}

    private static final int SAMPLE = 4096;
    private static final double RATIO = 0.30;

    public static boolean looksBinary(String s) {
        if (s == null || s.isEmpty()) return false;
        int n = Math.min(s.length(), SAMPLE);
        int suspicious = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == 0) return true;                       // NUL：铁证
            if (c == 0xFFFD) { suspicious++; continue; }        // 解码失败替换符
            boolean allowedControl = (c == '\n' || c == '\r' || c == '\t');
            if (c < 0x20 && !allowedControl) suspicious++;        // 其它控制字符
        }
        return (double) suspicious / n > RATIO;
    }
}

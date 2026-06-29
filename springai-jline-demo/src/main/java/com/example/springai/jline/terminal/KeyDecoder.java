package com.example.springai.jline.terminal;

/**
 * 纯逻辑：把方向键转义序列的「终结字节」映射为方向枚举。
 * 终端方向键序列形如 ESC '[' 'A'(上)/'B'(下)/'C'(右)/'D'(左)。
 * 本类只负责终结字节 -> 方向的映射，便于单测；读取转义序列的 IO 在场景里完成。
 */
public final class KeyDecoder {

    public enum Arrow { UP, DOWN, LEFT, RIGHT, NONE }

    private KeyDecoder() {
    }

    public static Arrow arrowFromCsiFinal(char finalByte) {
        return switch (finalByte) {
            case 'A' -> Arrow.UP;
            case 'B' -> Arrow.DOWN;
            case 'C' -> Arrow.RIGHT;
            case 'D' -> Arrow.LEFT;
            default -> Arrow.NONE;
        };
    }
}

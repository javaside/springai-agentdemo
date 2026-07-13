// BinarySniffTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BinarySniffTest {
    @Test
    void nulByte_isBinary() {
        assertTrue(BinarySniff.looksBinary("abc" + (char)0 + "def"));
    }

    @Test
    void manyReplacementChars_isBinary() {
        assertTrue(BinarySniff.looksBinary("" + (char)0xFFFD + (char)0xFFFD + (char)0xFFFD + (char)0xFFFD + "x"));
    }

    @Test
    void plainText_isNotBinary() {
        assertFalse(BinarySniff.looksBinary("normal source code\nline 2\n{json:1}"));
    }

    @Test
    void empty_isNotBinary() {
        assertFalse(BinarySniff.looksBinary(""));
        assertFalse(BinarySniff.looksBinary(null));
    }
}

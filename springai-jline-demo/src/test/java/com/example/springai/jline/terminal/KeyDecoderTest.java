package com.example.springai.jline.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyDecoderTest {

    @Test
    void csiFinalAIsUp() {
        assertEquals(KeyDecoder.Arrow.UP, KeyDecoder.arrowFromCsiFinal('A'));
    }

    @Test
    void csiFinalBIsDown() {
        assertEquals(KeyDecoder.Arrow.DOWN, KeyDecoder.arrowFromCsiFinal('B'));
    }

    @Test
    void csiFinalCIsRight() {
        assertEquals(KeyDecoder.Arrow.RIGHT, KeyDecoder.arrowFromCsiFinal('C'));
    }

    @Test
    void csiFinalDIsLeft() {
        assertEquals(KeyDecoder.Arrow.LEFT, KeyDecoder.arrowFromCsiFinal('D'));
    }

    @Test
    void unknownFinalIsNone() {
        assertEquals(KeyDecoder.Arrow.NONE, KeyDecoder.arrowFromCsiFinal('Z'));
    }
}

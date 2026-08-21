package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekVisionRegistryTest {

    @Test
    void put_take_roundTrip() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1, 2}, "image/png"));
        r.put(2, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-abc"));
        DeepSeekVisionMediaRegistry.Entry e0 = r.take(DeepSeekVisionMediaRegistry.key(0, 0));
        DeepSeekVisionMediaRegistry.Entry e1 = r.take(DeepSeekVisionMediaRegistry.key(2, 1));
        assertEquals(DeepSeekVisionMediaRegistry.Transport.INLINE, e0.transport());
        assertEquals("image/png", e0.mimeType());
        assertEquals(2, e0.bytes().length);
        assertEquals(DeepSeekVisionMediaRegistry.Transport.FILES, e1.transport());
        assertEquals("file-api-abc", e1.fileId());
        assertNull(e1.bytes());
    }

    @Test
    void take_consumes() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1}, "image/png"));
        assertTrue(r.take(DeepSeekVisionMediaRegistry.key(0, 0)) != null);
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(0, 0)), "take 消费即删，二次取应为 null");
    }

    @Test
    void unknownKey_returnsNull() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(9, 9)));
    }

    @Test
    void clear_empties() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1}, "image/png"));
        r.clear();
        assertTrue(r.isEmpty());
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(0, 0)));
    }

    @Test
    void key_format() {
        assertEquals("3:7", DeepSeekVisionMediaRegistry.key(3, 7));
    }
}

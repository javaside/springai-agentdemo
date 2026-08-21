package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekFileStoreTest {

    /** 记录每次上传的表单 + 返回可控响应。 */
    static final class RecordingUploader implements DeepSeekFileStore.Uploader {
        final List<MultiValueMap<String, Object>> calls = new ArrayList<>();
        final AtomicInteger failOn = new AtomicInteger(-1);   // 第 N 次抛异常（-1 不抛）
        int callCount() { return calls.size(); }

        @Override
        public String upload(MultiValueMap<String, Object> form) {
            calls.add(new LinkedMultiValueMap<>(form));
            if (failOn.get() == calls.size()) {
                throw new IllegalStateException("upload failed");
            }
            return "{\"id\":\"file-api-abc\",\"object\":\"file\"}";
        }
    }

    @Test
    void upload_form_containsFileAndPurpose() {
        RecordingUploader u = new RecordingUploader();
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        Optional<String> id = store.fileIdFor(new byte[]{1, 2}, "shot.png");

        assertTrue(id.isPresent());
        assertEquals("file-api-abc", id.get());
        assertEquals(1, u.callCount());
        MultiValueMap<String, Object> form = u.calls.get(0);
        assertEquals("user_data", form.getFirst("purpose"), "purpose 必须为 user_data");
        Object file = form.getFirst("file");
        assertTrue(file instanceof DeepSeekFileStore.NamedByteArrayResource, "file 必须是带文件名的 Resource");
        assertEquals("shot.png", ((DeepSeekFileStore.NamedByteArrayResource) file).getFilename());
    }

    @Test
    void sameBytes_uploadedOnce() {
        RecordingUploader u = new RecordingUploader();
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        store.fileIdFor(new byte[]{1, 2}, "a.png");
        store.fileIdFor(new byte[]{1, 2}, "b.png");
        assertEquals(1, u.callCount(), "同字节幂等：只上传一次，文件名差异不影响 sha 寻址");
    }

    @Test
    void uploadFailure_returnsEmpty() {
        RecordingUploader u = new RecordingUploader();
        u.failOn.set(1);
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        assertTrue(store.fileIdFor(new byte[]{1}, "a.png").isEmpty(), "上传失败必须返回 empty（调用方降级内联）");
    }

    @Test
    void parseFileId_variants() {
        assertEquals("file-api-x", DeepSeekFileStore.parseFileId("{\"id\":\"file-api-x\",\"object\":\"file\"}"));
        assertNull(DeepSeekFileStore.parseFileId("{}"), "缺 id 返回 null");
        assertNull(DeepSeekFileStore.parseFileId("{not-json"), "畸形返回 null");
        assertNull(DeepSeekFileStore.parseFileId("{\"id\":\"\"}"), "空白 id 返回 null");
    }

    @Test
    void namedResource_keepsFilename() {
        DeepSeekFileStore.NamedByteArrayResource r =
                new DeepSeekFileStore.NamedByteArrayResource(new byte[]{1}, "图.png");
        assertEquals("图.png", r.getFilename());
        assertEquals(1, r.contentLength());
    }
}

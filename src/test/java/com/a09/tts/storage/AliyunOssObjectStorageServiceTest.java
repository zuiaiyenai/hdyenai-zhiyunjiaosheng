package com.a09.tts.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliyunOssObjectStorageServiceTest {
    @Test
    void delegatesObjectLifecycleToConfiguredBucket() throws Exception {
        OSS client = mock(OSS.class);
        AliyunOssObjectStorageService storage =
                new AliyunOssObjectStorageService(client, "bucket-a");
        byte[] content = new byte[]{4, 5, 6};
        when(client.doesObjectExist("bucket-a", "voice_samples/alice/sample.wav"))
                .thenReturn(true);
        OSSObject object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(content));
        when(client.getObject("bucket-a", "voice_samples/alice/sample.wav"))
                .thenReturn(object);

        StoredObject stored = storage.store(
                "voice_samples/alice/sample.wav", new ByteArrayInputStream(content),
                content.length, "audio/wav", "checksum");

        ArgumentCaptor<ObjectMetadata> metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(client).putObject(eq("bucket-a"), eq(stored.objectKey()),
                any(InputStream.class), metadata.capture());
        assertEquals(content.length, metadata.getValue().getContentLength());
        assertEquals("audio/wav", metadata.getValue().getContentType());
        assertEquals("checksum", metadata.getValue().getUserMetadata().get("sha256"));
        assertEquals("aliyun-oss", stored.provider());
        assertEquals("bucket-a", stored.bucket());
        assertEquals(true, storage.exists(stored.objectKey()));
        try (var input = storage.open(stored.objectKey())) {
            assertArrayEquals(content, input.readAllBytes());
        }
        storage.delete(stored.objectKey());
        verify(client).deleteObject("bucket-a", stored.objectKey());
    }
}

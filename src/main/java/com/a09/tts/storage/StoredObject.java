package com.a09.tts.storage;

public record StoredObject(
        String objectKey,
        String provider,
        String bucket,
        String contentType,
        long size,
        String checksumSha256) {
}

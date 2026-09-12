package com.a09.tts.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorageService {
    StoredObject store(
            String objectKey,
            InputStream content,
            long size,
            String contentType,
            String checksumSha256) throws IOException;

    InputStream open(String objectKey) throws IOException;

    boolean exists(String objectKey) throws IOException;

    void delete(String objectKey) throws IOException;

    String provider();

    String bucket();
}

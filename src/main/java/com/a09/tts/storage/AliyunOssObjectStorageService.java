package com.a09.tts.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@Service
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "aliyun-oss")
public class AliyunOssObjectStorageService implements ObjectStorageService {
    private final OSS client;
    private final String bucket;

    @Autowired
    public AliyunOssObjectStorageService(
            @Value("${aliyun.oss.endpoint}") String endpoint,
            @Value("${aliyun.oss.bucket-name}") String bucket,
            @Value("${aliyun.oss.access-key-id}") String accessKeyId,
            @Value("${aliyun.oss.access-key-secret}") String accessKeySecret,
            @Value("${aliyun.oss.connection-timeout:5s}") Duration connectionTimeout,
            @Value("${aliyun.oss.socket-timeout:30s}") Duration socketTimeout,
            @Value("${aliyun.oss.request-timeout:2m}") Duration requestTimeout,
            @Value("${aliyun.oss.max-connections:64}") int maxConnections) {
        requireConfigured(endpoint, "endpoint");
        requireConfigured(bucket, "bucket-name");
        requireConfigured(accessKeyId, "access-key-id");
        requireConfigured(accessKeySecret, "access-key-secret");
        ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
        configuration.setConnectionTimeout(Math.toIntExact(connectionTimeout.toMillis()));
        configuration.setSocketTimeout(Math.toIntExact(socketTimeout.toMillis()));
        configuration.setRequestTimeout(Math.toIntExact(requestTimeout.toMillis()));
        configuration.setRequestTimeoutEnabled(true);
        configuration.setMaxConnections(maxConnections);
        String normalizedEndpoint = endpoint.startsWith("http://") || endpoint.startsWith("https://")
                ? endpoint : "https://" + endpoint;
        this.client = new OSSClientBuilder().build(
                normalizedEndpoint, accessKeyId, accessKeySecret, configuration);
        this.bucket = bucket;
    }

    AliyunOssObjectStorageService(OSS client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public StoredObject store(
            String objectKey,
            InputStream content,
            long size,
            String contentType,
            String checksumSha256) throws IOException {
        String key = ObjectStorageKeys.requireValid(objectKey);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(size);
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        if (checksumSha256 != null && !checksumSha256.isBlank()) {
            metadata.addUserMetadata("sha256", checksumSha256);
        }
        try {
            client.putObject(bucket, key, content, metadata);
            return new StoredObject(key, provider(), bucket, contentType, size, checksumSha256);
        } catch (OSSException | ClientException exception) {
            throw new IOException("阿里云 OSS 上传失败", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) throws IOException {
        String key = ObjectStorageKeys.requireValid(objectKey);
        try {
            return client.getObject(bucket, key).getObjectContent();
        } catch (OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) {
                throw new FileNotFoundException("OSS 对象不存在");
            }
            throw new IOException("阿里云 OSS 下载失败", exception);
        } catch (ClientException exception) {
            throw new IOException("阿里云 OSS 下载失败", exception);
        }
    }

    @Override
    public boolean exists(String objectKey) throws IOException {
        try {
            return client.doesObjectExist(bucket, ObjectStorageKeys.requireValid(objectKey));
        } catch (OSSException | ClientException exception) {
            throw new IOException("阿里云 OSS 查询失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) throws IOException {
        try {
            client.deleteObject(bucket, ObjectStorageKeys.requireValid(objectKey));
        } catch (OSSException | ClientException exception) {
            throw new IOException("阿里云 OSS 删除失败", exception);
        }
    }

    @Override
    public String provider() {
        return "aliyun-oss";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @PreDestroy
    public void shutdown() {
        client.shutdown();
    }

    private static void requireConfigured(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("aliyun.oss." + name + " 未配置");
        }
    }
}

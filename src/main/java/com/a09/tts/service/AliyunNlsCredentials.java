package com.a09.tts.service;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.profile.DefaultProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

@Component
public class AliyunNlsCredentials {
    private static final long TOKEN_REFRESH_AHEAD_SECONDS = 300;

    private final String appKey;
    private final String configuredToken;
    private final String websocketUrl;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final TokenFetcher tokenFetcher;
    private final LongSupplier epochSeconds;
    private volatile CachedToken cachedToken;

    @Autowired
    public AliyunNlsCredentials(
            @Value("${aliyun.nls.app-key:}") String appKey,
            @Value("${aliyun.nls.token:}") String token,
            @Value("${aliyun.nls.websocket-url:wss://nls-gateway-cn-beijing.aliyuncs.com/ws/v1}")
            String websocketUrl,
            @Value("${aliyun.nls.access-key-id:}") String accessKeyId,
            @Value("${aliyun.nls.access-key-secret:}") String accessKeySecret) {
        this(appKey, token, websocketUrl, accessKeyId, accessKeySecret,
                (keyId, keySecret) -> {
                    AccessToken accessToken = new AccessToken(keyId, keySecret);
                    accessToken.apply();
                    return new FetchedToken(accessToken.getToken(), accessToken.getExpireTime());
                },
                () -> System.currentTimeMillis() / 1000);
    }

    AliyunNlsCredentials(String appKey, String token, String websocketUrl,
                         String accessKeyId, String accessKeySecret,
                         TokenFetcher tokenFetcher, LongSupplier epochSeconds) {
        this.appKey = appKey.trim();
        this.configuredToken = token.trim();
        this.websocketUrl = websocketUrl.trim();
        this.accessKeyId = accessKeyId.trim();
        this.accessKeySecret = accessKeySecret.trim();
        this.tokenFetcher = tokenFetcher;
        this.epochSeconds = epochSeconds;
    }

    public String appKey() {
        return appKey;
    }

    public NlsClient createNlsClient() {
        requireNlsConfiguration();
        return new NlsClient(websocketUrl, resolveToken());
    }

    public IAcsClient createAcsClient(String region) {
        requireCloneConfiguration();
        return new DefaultAcsClient(DefaultProfile.getProfile(region, accessKeyId, accessKeySecret));
    }

    public List<String> missingNlsConfiguration() {
        List<String> missing = new ArrayList<>();
        if (appKey.isBlank()) {
            missing.add("ALIYUN_NLS_APP_KEY");
        }
        if (!hasAutomaticTokenCredentials() && configuredToken.isBlank()) {
            missing.add("ALIYUN_NLS_TOKEN 或 ALIYUN_AK_ID/ALIYUN_AK_SECRET");
        }
        return missing;
    }

    public List<String> missingCloneConfiguration() {
        List<String> missing = new ArrayList<>(missingNlsConfiguration());
        if (accessKeyId.isBlank()) {
            missing.add("ALIYUN_AK_ID");
        }
        if (accessKeySecret.isBlank()) {
            missing.add("ALIYUN_AK_SECRET");
        }
        return missing;
    }

    public String tokenMode() {
        return hasAutomaticTokenCredentials() ? "auto-refresh"
                : (configuredToken.isBlank() ? "unavailable" : "configured");
    }

    public void requireNlsConfiguration() {
        if (appKey.isBlank()) {
            throw new IllegalStateException("请配置 ALIYUN_NLS_APP_KEY");
        }
        if (!hasAutomaticTokenCredentials() && configuredToken.isBlank()) {
            throw new IllegalStateException(
                    "请配置 ALIYUN_AK_ID/ALIYUN_AK_SECRET 以自动刷新 Token，或配置临时 ALIYUN_NLS_TOKEN");
        }
    }

    public void requireCloneConfiguration() {
        requireNlsConfiguration();
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new IllegalStateException("请配置 ALIYUN_AK_ID 和 ALIYUN_AK_SECRET");
        }
    }

    private boolean hasAutomaticTokenCredentials() {
        return !accessKeyId.isBlank() && !accessKeySecret.isBlank();
    }

    synchronized String resolveToken() {
        if (!hasAutomaticTokenCredentials()) {
            return configuredToken;
        }

        long now = epochSeconds.getAsLong();
        CachedToken current = cachedToken;
        if (current != null && now < current.expireTime() - TOKEN_REFRESH_AHEAD_SECONDS) {
            return current.value();
        }

        try {
            FetchedToken fetchedToken = tokenFetcher.fetch(accessKeyId, accessKeySecret);
            String value = fetchedToken.value();
            long expireTime = fetchedToken.expireTime();
            if (value == null || value.isBlank() || expireTime <= now) {
                throw new IllegalStateException("阿里云 CreateToken 返回了无效结果");
            }
            cachedToken = new CachedToken(value, expireTime);
            return value;
        } catch (Exception exception) {
            throw new IllegalStateException("自动获取阿里云 NLS Token 失败：" + exception.getMessage(), exception);
        }
    }

    private record CachedToken(String value, long expireTime) {
    }

    record FetchedToken(String value, long expireTime) {
    }

    @FunctionalInterface
    interface TokenFetcher {
        FetchedToken fetch(String accessKeyId, String accessKeySecret) throws Exception;
    }
}

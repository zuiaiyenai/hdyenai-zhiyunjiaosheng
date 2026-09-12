package com.a09.tts.storage;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public final class ObjectStorageKeys {
    private ObjectStorageKeys() {
    }

    public static String voiceSample(String owner, String originalFilename) {
        return "voice_samples/" + ownerKey(owner) + "/" + UUID.randomUUID()
                + extension(originalFilename);
    }

    public static String requireValid(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.length() > 1024
                || objectKey.startsWith("/") || objectKey.startsWith("\\")
                || objectKey.contains("\\") || objectKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("对象存储键非法");
        }
        for (String part : objectKey.split("/", -1)) {
            if (part.isBlank() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("对象存储键非法");
            }
        }
        return objectKey;
    }

    public static String filename(String objectKey) {
        String valid = requireValid(objectKey);
        return valid.substring(valid.lastIndexOf('/') + 1);
    }

    private static String extension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("非法上传文件名");
        }
        String decoded = originalFilename;
        for (int i = 0; i < 2; i++) {
            decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
        }
        if (decoded.contains("/") || decoded.contains("\\") || decoded.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("非法上传文件名");
        }
        int dot = decoded.lastIndexOf('.');
        return dot < 0 ? "" : decoded.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String ownerKey(String owner) {
        try {
            String normalized = owner == null || owner.isBlank()
                    ? "anonymous" : owner.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建对象存储用户键", exception);
        }
    }
}

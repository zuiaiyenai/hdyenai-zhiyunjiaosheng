package com.a09.tts.pojo;

import java.io.Serializable;
import java.time.LocalDateTime;

public class Voice implements Serializable {
    private static final long serialVersionUID = 1L;

    private Integer voiceId;
    private String voiceName;
    private String applicationScene;
    private String filePath;
    private String objectKey;
    private String storageProvider;
    private String storageBucket;
    private String mimeType;
    private Long fileSize;
    private String checksumSha256;
    private Boolean publicVisible;
    private String ownerUsername;
    private LocalDateTime createdAt;

    public Integer getVoiceId() { return voiceId; }
    public void setVoiceId(Integer voiceId) { this.voiceId = voiceId; }
    public String getVoiceName() { return voiceName; }
    public void setVoiceName(String voiceName) { this.voiceName = voiceName; }
    public String getApplicationScene() { return applicationScene; }
    public void setApplicationScene(String applicationScene) { this.applicationScene = applicationScene; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }
    public String getStorageBucket() { return storageBucket; }
    public void setStorageBucket(String storageBucket) { this.storageBucket = storageBucket; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }
    public Boolean getPublicVisible() { return publicVisible; }
    public void setPublicVisible(Boolean publicVisible) { this.publicVisible = publicVisible; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String ownerUsername) { this.ownerUsername = ownerUsername; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

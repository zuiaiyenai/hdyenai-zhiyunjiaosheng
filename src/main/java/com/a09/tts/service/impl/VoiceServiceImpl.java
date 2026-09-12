package com.a09.tts.service.impl;

import com.a09.tts.cleanup.PendingFileCleanupService;
import com.a09.tts.mapper.VoiceMapper;
import com.a09.tts.pojo.Voice;
import com.a09.tts.service.VoiceService;
import com.a09.tts.storage.ObjectStorageKeys;
import com.a09.tts.storage.ObjectStorageService;
import com.a09.tts.storage.StoredObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("!nodb")
public class VoiceServiceImpl implements VoiceService {

    @Autowired
    private VoiceMapper voiceMapper;

    @Autowired
    private UploadSecurityService uploadSecurity;

    @Autowired
    private ObjectStorageService objectStorage;

    @Autowired
    private PendingFileCleanupService pendingFileCleanupService;

    public List<Voice> findVoiceByName(String voiceName) {
        return voiceMapper.findVoiceByName(voiceName);
    }

    public List<Voice> findVisibleVoiceByName(String voiceName, String username) {
        return voiceMapper.findVisibleVoiceByName(voiceName, username);
    }

    @Cacheable("voiceList")
    public List<Voice> findAllVoices() {
        return voiceMapper.findAllVoices();
    }

    @Cacheable(value = "voiceList", key = "#username")
    public List<Voice> findVisibleVoices(String username) {
        return voiceMapper.findVisibleVoices(username);
    }

    @Caching(evict = {
            @CacheEvict(value = "voiceList", allEntries = true),
            @CacheEvict(value = "voiceById", key = "#voiceId")
    })
    @Transactional
    public int deleteVoiceById(int voiceId) {
        Voice voice = voiceMapper.findVoiceById(voiceId);
        int deleted = voiceMapper.deleteVoiceById(voiceId);
        if (deleted == 1 && voice != null && voice.getFilePath() != null) {
            String storageType = voice.getObjectKey() == null
                    ? PendingFileCleanupService.VOICE_STORAGE
                    : PendingFileCleanupService.VOICE_OBJECT_STORAGE;
            String storageKey = voice.getObjectKey() == null
                    ? voice.getFilePath() : voice.getObjectKey();
            Runnable cleanup = () -> pendingFileCleanupService.deleteOrEnqueue(
                    storageType, storageKey);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                cleanup.run();
                            }
                        });
            } else {
                cleanup.run();
            }
        }
        return deleted;
    }

    @Caching(evict = {
            @CacheEvict(value = "voiceList", allEntries = true),
            @CacheEvict(value = "voiceById", key = "#voice.voiceId")
    })
    public int updateVoiceSample(Voice voice) {
        return voiceMapper.updateVoiceSample(voice);
    }

    @Caching(evict = {
            @CacheEvict(value = "voiceList", allEntries = true),
            @CacheEvict(value = "voiceById", allEntries = true)
    })
    public Voice upload(String name, String scene, boolean publicVisible, String owner, MultipartFile file)
            throws Exception {
        uploadSecurity.validate(file, Type.AUDIO);
        Long usedBytes = voiceMapper.sumStoredBytesByOwner(owner);
        uploadSecurity.ensureQuota(usedBytes == null ? 0 : usedBytes, file.getSize());
        String key = ObjectStorageKeys.voiceSample(owner, file.getOriginalFilename());
        String checksum = uploadSecurity.sha256(file);
        StoredObject stored;
        try (var input = file.getInputStream()) {
            stored = objectStorage.store(
                    key, input, file.getSize(), file.getContentType(), checksum);
        }
        Voice voice = new Voice();
        voice.setVoiceName(name);
        voice.setApplicationScene(scene);
        voice.setFilePath(stored.objectKey());
        voice.setObjectKey(stored.objectKey());
        voice.setStorageProvider(stored.provider());
        voice.setStorageBucket(stored.bucket());
        voice.setMimeType(stored.contentType());
        voice.setFileSize(stored.size());
        voice.setChecksumSha256(stored.checksumSha256());
        voice.setPublicVisible(publicVisible);
        voice.setOwnerUsername(owner);
        try {
            voiceMapper.addVoiceSample(voice);
            return voice;
        } catch (Exception exception) {
            pendingFileCleanupService.deleteOrEnqueue(
                    PendingFileCleanupService.VOICE_OBJECT_STORAGE, voice.getObjectKey());
            throw exception;
        }
    }

    @Cacheable(value = "voiceById", key = "#voiceId", unless = "#result == null")
    public Voice findById(int voiceId) {
        return voiceMapper.findVoiceById(voiceId);
    }
}

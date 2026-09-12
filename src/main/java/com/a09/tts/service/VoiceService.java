package com.a09.tts.service;

import com.a09.tts.pojo.Voice;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VoiceService {

    List<Voice> findVisibleVoiceByName(
            String voiceName, String username, int offset, int limit);

    List<Voice> findVisibleVoices(String username, int offset, int limit);

    int deleteVoiceById(int voiceId);

    int updateVoiceSample(Voice voice);

    Voice upload(String name, String scene, boolean publicVisible, String owner, MultipartFile file)
            throws Exception;

    Voice findById(int voiceId);
}

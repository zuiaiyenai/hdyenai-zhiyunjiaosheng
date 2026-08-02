package com.a09.tts.service;

import com.a09.tts.api.AsrResult;

public interface ASRService {

    String transcribe(String filePath, String language);

    AsrResult transcribeDetailed(String filePath, String language);
}

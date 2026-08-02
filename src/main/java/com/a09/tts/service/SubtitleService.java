package com.a09.tts.service;

public interface SubtitleService {

    String generateSubtitles(String audioFilePath, String language) throws Exception;
}

package com.a09.tts.service;

public interface SubtitleService {

    String generateSubtitles(String audioObjectKey, String language, String owner) throws Exception;
}

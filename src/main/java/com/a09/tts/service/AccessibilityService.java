package com.a09.tts.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface AccessibilityService {

    Map<String, Object> readTextFile(MultipartFile file) throws Exception;

    Map<String, Object> saveVoiceNote(MultipartFile audioFile, String title) throws Exception;

    Map<String, Object> listVoiceNotes() throws Exception;

    Map<String, Object> generateStudySummary(String textContent);

    Map<String, Object> readPPTFile(MultipartFile file) throws Exception;
}

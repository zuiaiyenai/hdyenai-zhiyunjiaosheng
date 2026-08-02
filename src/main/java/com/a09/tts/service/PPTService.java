package com.a09.tts.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface PPTService {

    String processPptAndGenerateContent(MultipartFile file) throws IOException;
}

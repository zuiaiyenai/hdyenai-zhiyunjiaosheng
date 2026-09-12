package com.a09.tts.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;

public interface PPTService {

    String processPptAndGenerateContent(MultipartFile file) throws IOException;

    String processPptAndGenerateContent(Path file, String originalFilename) throws IOException;

    String optimizeCoursewareContent(String currentScript, String instruction);
}

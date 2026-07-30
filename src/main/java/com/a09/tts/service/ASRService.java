package com.a09.tts.service;

import com.a09.tts.util.JsonToEntity;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

@Service
public class ASRService {

    private static final String API_URL = "http://vop.baidu.com/server_api";  // 请替换为你的 ASR API
    private static final String ACCESS_TOKEN = "24.139c7db7ffff541178434f9e535282f6.2592000.1742436048.282335-117574";

    private final JsonToEntity jsonToEntity = new JsonToEntity();
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 🎙 **ASR 音频转文本**
     *
     * @param filePath  音频文件路径
     * @param language  语音语言，如："zh"（中文） / "en"（英文）
     * @return  转换后的文本
     */
    public String transcribe(String filePath, String language) {
        MultiValueMap<String, Object> requestBody = new LinkedMultiValueMap<>();
        requestBody.add("file", new File(filePath));
        requestBody.add("language", language);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAcceptCharset(Arrays.asList(StandardCharsets.UTF_8));
        headers.set("Authorization", "Bearer " + ACCESS_TOKEN); // Baidu 语音识别 API 需要授权

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(new URI(API_URL), requestEntity, String.class);
            String resultText = jsonToEntity.oneAttribute("text", response.getBody());

            saveResultToFile(resultText, "transcript.txt");

            return resultText;
        } catch (URISyntaxException e) {
            System.err.println("⛔ Invalid URI: " + e.getMessage());
            return null;
        }
    }

    /**
     * 💾 **保存转换文本到本地文件**
     *
     * @param resultText  转换后的文本内容
     * @param filename  保存的文件名
     */
    private void saveResultToFile(String resultText, String filename) {
        try {
            Path outputPath = Paths.get("output/" + filename);
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, resultText.getBytes(StandardCharsets.UTF_8));
            System.out.println("✅ 结果已保存至: " + outputPath);
        } catch (Exception e) {
            System.err.println("⛔ 保存文本文件失败: " + e.getMessage());
        }
    }
}
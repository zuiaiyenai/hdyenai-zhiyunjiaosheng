package com.a09.tts.service;

import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 声音克隆服务类
 */

@Service
public class SoundCloneService {

    public ResponseEntity<byte[]> soundClone(
            String promptText,
            String promptLang,
            String text,
            String textLang,
            String audioFilePath) {

        // 创建 RestTemplate 实例
        RestTemplate restTemplate = new RestTemplate();

        // 定义请求地址
        String url = "http://127.0.0.1:9880/tts";

        //将音频文件的路径字符串格式修改一下，把“\”变为“/”，符合json规范
        String new_audioFilePath = audioFilePath.replace("\\", "/");

        // 定义请求参数
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("text", text);  // 要转换的文本
        requestBody.put("text_lang", textLang);                 // 文本语言，如zh...
        requestBody.put("ref_audio_path", new_audioFilePath);  // 参考音频路径
        requestBody.put("aux_ref_audio_paths", new ArrayList<>());  // 辅助参考音频路径（空列表）
        requestBody.put("prompt_lang", promptLang);               // 提示文本语言
        requestBody.put("prompt_text", promptText);          // 提示文本
        requestBody.put("top_k", 5);                        // 采样候选数
        requestBody.put("top_p", 1.0);                      // 核心采样概率
        requestBody.put("temperature", 1.0);                // 温度参数
        requestBody.put("text_split_method", "cut5");       // 文本分割方法
        requestBody.put("batch_size", 1);                   // 批处理大小
        requestBody.put("batch_threshold", 0.75);           // 批处理阈值
        requestBody.put("split_bucket", true);              // 是否分割桶
        requestBody.put("speed_factor", 1.0);               // 语速因子
        requestBody.put("fragment_interval", 0.3);          // 片段间隔
        requestBody.put("seed", 42);                        // 随机种子
        requestBody.put("media_type", "wav");               // 输出媒体类型
        requestBody.put("streaming_mode", false);           // 是否流式传输
        requestBody.put("parallel_infer", true);            // 是否并行推理
        requestBody.put("repetition_penalty", 1.35);        // 重复惩罚系数

        // 设置请求头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAcceptCharset(java.util.Collections.singletonList(StandardCharsets.UTF_8));

        // 创建 HttpEntity 对象，包含请求体和请求头
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            // 发送 POST 请求并获取响应
            ResponseEntity<byte[]> response = restTemplate.postForEntity(url, requestEntity, byte[].class);
            // 设置响应头
            HttpHeaders responseHeaders = new HttpHeaders();
            // 设置响应内容类型为音频
            responseHeaders.setContentType(MediaType.valueOf("audio/wav"));
            // 设置响应内容的处理方式为附件下载，并指定文件名
            responseHeaders.setContentDisposition(ContentDisposition.attachment().filename("output.wav").build());

            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());

        } catch (Exception e) {
            // 更详细的异常处理，例如日志记录
            System.err.println("Invalid URI: " + e.getMessage());
            return null;
        }
    }
}

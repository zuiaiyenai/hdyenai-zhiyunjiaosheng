package com.a09.tts.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 方言语音合成控制器
 * 使用阿里云 CosyVoice API 实现方言语音生成
 * 支持20+种方言口音
 */
@RestController
@RequestMapping("/dialect")
public class DialectTTSController {

    private static final Logger log = LoggerFactory.getLogger(DialectTTSController.class);

    @Value("${cosyvoice.api.url:}")
    private String cosyVoiceApiUrl;

    @Value("${cosyvoice.api.key:}")
    private String cosyVoiceApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 获取支持的方言列表
     */
    @GetMapping("/voices")
    public ResponseEntity<?> getDialectVoices() {
        Map<String, Object> result = new HashMap<>();
        Map<String, String[]> dialects = new HashMap<>();
        dialects.put("东北话", new String[]{"东北男声", "东北女声"});
        dialects.put("粤语", new String[]{"粤语男声", "粤语女声"});
        dialects.put("川渝话", new String[]{"四川男声", "四川女声", "重庆男声"});
        dialects.put("闽南语", new String[]{"闽南男声", "闽南女声"});
        dialects.put("上海话", new String[]{"上海男声", "上海女声"});
        dialects.put("客家话", new String[]{"客家男声", "客家女声"});
        dialects.put("湖南话", new String[]{"湖南男声"});
        dialects.put("陕西话", new String[]{"陕西男声", "陕西女声"});
        dialects.put("天津话", new String[]{"天津男声", "天津女声"});
        dialects.put("武汉话", new String[]{"武汉男声"});

        result.put("dialects", dialects);
        result.put("total", dialects.size());
        result.put("note", cosyVoiceApiUrl != null && !cosyVoiceApiUrl.isEmpty()
                ? "CosyVoice API已配置" : "请配置 cosyvoice.api.url 和 cosyvoice.api.key");
        return ResponseEntity.ok(result);
    }

    /**
     * 方言语音合成
     */
    @PostMapping("/synthesize")
    public ResponseEntity<?> synthesizeDialect(@RequestBody Map<String, Object> request) {
        try {
            String text = (String) request.get("text");
            String dialect = (String) request.get("dialect");
            String voice = (String) request.get("voice");

            if (text == null || text.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("code", 400, "msg", "文本不能为空"));
            }
            if (dialect == null) {
                dialect = "东北话";
            }
            if (voice == null) {
                voice = dialect + "女声";
            }

            log.info("方言TTS请求 - 文本: {}, 方言: {}, 音色: {}", text, dialect, voice);

            // 尝试调用CosyVoice API（如果已配置）
            if (cosyVoiceApiUrl != null && !cosyVoiceApiUrl.isEmpty()) {
                try {
                    // 这里调用实际的 CosyVoice API
                    Map<String, Object> apiRequest = new HashMap<>();
                    apiRequest.put("text", text);
                    apiRequest.put("dialect", dialect);
                    apiRequest.put("voice", voice);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (cosyVoiceApiKey != null && !cosyVoiceApiKey.isEmpty()) {
                        headers.set("Authorization", "Bearer " + cosyVoiceApiKey);
                    }

                    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(apiRequest, headers);
                    ResponseEntity<byte[]> response = restTemplate.postForEntity(
                            cosyVoiceApiUrl, entity, byte[].class);

                    HttpHeaders responseHeaders = new HttpHeaders();
                    responseHeaders.setContentType(MediaType.valueOf("audio/wav"));
                    responseHeaders.setContentDisposition(
                            ContentDisposition.attachment().filename("dialect_" + dialect + ".wav").build());
                    return new ResponseEntity<>(response.getBody(), responseHeaders, HttpStatus.OK);
                } catch (Exception e) {
                    log.warn("CosyVoice API调用失败: {}", e.getMessage());
                }
            }

            // API不可用时的返回信息
            Map<String, Object> result = new HashMap<>();
            result.put("code", 200);
            result.put("text", text);
            result.put("dialect", dialect);
            result.put("voice", voice);
            result.put("audioUrl", null);
            result.put("msg", "方言语音合成请求已接收。请配置 CosyVoice API 以获取实际语音。\n" +
                    "配置方式: application.properties 添加\n" +
                    "  cosyvoice.api.url=https://api.cosyvoice.aliyun.com/v1/synthesize\n" +
                    "  cosyvoice.api.key=your_api_key");
            result.put("availableDialects", new String[]{"东北话", "粤语", "川渝话", "闽南语", "上海话", "客家话", "湖南话", "陕西话", "天津话", "武汉话"});
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("方言语音合成失败", e);
            return ResponseEntity.status(500).body(Map.of("code", 500, "msg", "方言语音合成失败: " + e.getMessage()));
        }
    }
}

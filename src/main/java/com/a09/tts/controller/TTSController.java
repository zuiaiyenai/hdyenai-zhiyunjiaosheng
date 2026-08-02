package com.a09.tts.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.a09.tts.api.TtsRequest;
import com.a09.tts.service.TTSService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/voice")
public class TTSController {

    private static final Logger log = LoggerFactory.getLogger(TTSController.class);

    @Autowired
    private TTSService ttsService;

    /**
     * 处理tts的控制器方法
     *
     * @param requestData 参数是包含文本和声音样本选择的json字符串
     * @return 返回的是生成的语音
     */

    @PostMapping("/synthesize")
    public ResponseEntity<byte[]> textToSpeech(@Valid @RequestBody TtsRequest request) {
        return ttsService.tts(request.text(), request.voice(), request.effectiveSpeed(),
                request.effectivePitch(), request.effectiveRhythm());
    }

}

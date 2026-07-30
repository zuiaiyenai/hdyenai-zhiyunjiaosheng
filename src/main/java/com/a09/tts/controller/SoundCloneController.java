package com.a09.tts.controller;

import com.a09.tts.service.SoundCloneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;


@Slf4j
@RestController
@RequestMapping("/sound_clone")
public class SoundCloneController {

    @Value("${app.upload-dir}")
    private String uploadDir;

    @Autowired
    private SoundCloneService soundCloneService;

    @PostMapping("/upload")
    public ResponseEntity<?> soundClone(
            @RequestParam("prompt_text") String promptText,//表示音频文件的文本内容
            @RequestParam("prompt_lang") String promptLang,//表示音频文件的文本语言
            @RequestParam("text") String text,//表示要生成的文本
            @RequestParam("text_lang") String textLang,//表示要生成的文本语言
            @RequestParam("audioFile") MultipartFile audioFile) {

        try {
            // 1. 验证文件是否为空
            if (audioFile.isEmpty()) {
                return ResponseEntity.badRequest().body("音频文件为空！");
            }

            // 2. 处理字符串参数（打印参数）
            System.out.println("声音克隆模型接收到的参数为：");
            System.out.println("prompt_text: " + promptText);
            System.out.println("prompt_lang: " + promptLang);
            System.out.println("text: " + text);
            System.out.println("text_lang: " + textLang);

            // 3. 处理音频文件（保存到本地服务器）
            // 使用配置的路径
            Path dirPath = Paths.get(uploadDir);
            //指定音频文件名
            String audioFileName = System.currentTimeMillis() + "_" + audioFile.getOriginalFilename();
            // 构建完整路径
            Path audioFilePath = dirPath.resolve(audioFileName);
            // 保存文件
            Files.copy(audioFile.getInputStream(), audioFilePath, StandardCopyOption.REPLACE_EXISTING);

            // 4. 调用api开始克隆
            ResponseEntity<byte[]> response = soundCloneService.soundClone(promptText, promptLang, text, textLang, audioFilePath.toString());
            if (response.getStatusCode() == HttpStatus.OK) {
                //克隆成功以后删除用户上传的音频文件
                audioFilePath.toFile().deleteOnExit();
                //从响应体中取出音频文件字节流
                byte[] audioData = response.getBody();
                //获取SoundCloneService中设置好的响应头，确保前端收到的是处理好的音频文件
                HttpHeaders headers = response.getHeaders();
                return new ResponseEntity<>(audioData, headers,HttpStatus.OK);
            } else {
                return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Upload failed: " + e.getMessage());
        }
    }

}

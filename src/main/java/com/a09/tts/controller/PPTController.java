package com.a09.tts.controller;



import com.a09.tts.service.PPTService;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;


/**
 * 课件(PPT)总结的控制器类
 * 调用kimi开源大模型api，将前端传入的ppt文件概括成文本课件，供用户使用
 *
 */

@RestController
@RequestMapping("/courseware")
public class PPTController {

    @Autowired
    private PPTService pptService;

    @Autowired
    private UploadSecurityService uploadSecurity;

    @PostMapping("/summary")
    public ResponseEntity<String> summary(@RequestParam("file") MultipartFile file) throws Exception {
        uploadSecurity.validate(file, Type.PRESENTATION);
        String result = pptService.processPptAndGenerateContent(file);
        return ResponseEntity.ok(result);
    }

}

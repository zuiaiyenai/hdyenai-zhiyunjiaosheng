package com.a09.tts.controller;



import com.a09.tts.task.AsyncTaskService.TaskSubmission;
import com.a09.tts.task.MediaTaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
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
    private final MediaTaskService mediaTasks;

    public PPTController(MediaTaskService mediaTasks) {
        this.mediaTasks = mediaTasks;
    }

    @PostMapping("/summary")
    public ResponseEntity<TaskSubmission> summary(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws Exception {
        Object username = request.getAttribute("username");
        TaskSubmission submission = mediaTasks.submitPptSummary(
                file, username == null ? "anonymous" : username.toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(submission);
    }

}

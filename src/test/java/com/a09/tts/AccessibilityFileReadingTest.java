package com.a09.tts;

import com.a09.tts.service.MoonshotChatClient;
import com.a09.tts.service.impl.AccessibilityServiceImpl;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AccessibilityFileReadingTest {

    @Test
    void readsUtf8TextFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.txt", "text/plain",
                "量子科技".getBytes(StandardCharsets.UTF_8));
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(
                mock(MoonshotChatClient.class));

        Map<String, Object> result = service.readTextFile(file);

        assertEquals("量子科技", result.get("text"));
        assertEquals("lesson.txt", result.get("fileName"));
    }

    @Test
    void extractsTextFromDocx() throws Exception {
        byte[] docx;
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("量子科技");
            document.createParagraph().createRun().setText("文档解析成功");
            document.write(output);
            docx = output.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "量子科技.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx);
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(
                mock(MoonshotChatClient.class));

        Map<String, Object> result = service.readTextFile(file);

        String text = (String) result.get("text");
        assertTrue(text.contains("量子科技"));
        assertTrue(text.contains("文档解析成功"));
        assertEquals("量子科技.docx", result.get("fileName"));
    }
}

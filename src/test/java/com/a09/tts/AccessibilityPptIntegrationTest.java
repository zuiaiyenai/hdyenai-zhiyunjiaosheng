package com.a09.tts;

import com.a09.tts.service.MoonshotChatClient;
import com.a09.tts.service.impl.AccessibilityServiceImpl;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AccessibilityPptIntegrationTest {

    @Test
    void readPptFileExtractsTextFromPptx() throws Exception {
        byte[] pptx;
        try (XMLSlideShow slideShow = new XMLSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide slide = slideShow.createSlide();
            addTextBox(slide, "智韵教声 · 真实环境联调");
            addTextBox(slide, "AI 语音教学");
            addTextBox(slide, "文本转语音 · 语音识别 · 学习纪要");
            slideShow.write(output);
            pptx = output.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "moonshot-ppt-e2e.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx);
        AccessibilityServiceImpl service = new AccessibilityServiceImpl(mock(MoonshotChatClient.class));

        Map<String, Object> result = service.readPPTFile(file);

        String text = (String) result.get("text");
        assertTrue(text.contains("智韵教声 · 真实环境联调"));
        assertTrue(text.contains("AI 语音教学"));
        assertTrue(text.contains("文本转语音 · 语音识别 · 学习纪要"));
        assertFalse(text.startsWith("PK"));
        assertEquals("PPT文件解析成功", result.get("message"));
        assertEquals("moonshot-ppt-e2e.pptx", result.get("fileName"));
    }

    private void addTextBox(XSLFSlide slide, String text) {
        XSLFTextBox textBox = slide.createTextBox();
        textBox.setText(text);
    }
}

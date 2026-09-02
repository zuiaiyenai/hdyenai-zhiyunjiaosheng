package com.a09.tts.service;

import com.a09.tts.service.CoursewareProjectService.DownloadArtifact;
import com.a09.tts.service.CoursewareProjectService.ProjectView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoursewareProjectServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void keepsScriptRevisionsScopesOwnerAndPackagesGeneratedAudio() throws Exception {
        PPTService pptService = mock(PPTService.class);
        TTSService ttsService = mock(TTSService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("第一版教学讲稿");
        when(pptService.optimizeCoursewareContent(anyString(), anyString()))
                .thenReturn("第二版教学讲稿，包含课堂提问");
        when(ttsService.tts(anyString(), eq("longxiao"), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(ResponseEntity.ok(new byte[]{82, 73, 70, 70}));

        CoursewareProjectService service = new CoursewareProjectService(pptService, ttsService);
        ReflectionTestUtils.setField(service, "coursewareDir", tempDirectory.toString());
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(service, "ffprobePath", "ffprobe");

        MockMultipartFile ppt = new MockMultipartFile("file", "人工智能导论.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx());

        ProjectView created = service.create(ppt, "alice");
        assertEquals("人工智能导论", created.title());
        assertEquals(0, created.revision());
        assertThrows(IllegalArgumentException.class, () -> service.get(created.id(), "bob"));

        ProjectView optimized = service.optimize(created.id(), "alice", "增加课堂提问");
        assertEquals(1, optimized.revision());
        assertTrue(optimized.script().contains("课堂提问"));

        ProjectView withAudio = service.generateAudio(created.id(), "alice",
                "longxiao", 1.1, 0.9, 1.0);
        assertTrue(withAudio.audioReady());

        DownloadArtifact artifact = service.download(created.id(), "alice", "package");
        assertTrue(artifact.path().toFile().isFile());
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            assertNotNull(zip.getEntry("人工智能导论.pptx"));
            assertNotNull(zip.getEntry("讲稿/当前讲稿.txt"));
            assertNotNull(zip.getEntry("讲稿/历史版本-00.txt"));
            assertNotNull(zip.getEntry("讲稿/历史版本-01.txt"));
            assertNotNull(zip.getEntry("媒体/讲稿语音.wav"));
        }
    }

    @Test
    void rejectsFriendlyAiFailureInsteadOfSavingItAsScript() throws Exception {
        PPTService pptService = mock(PPTService.class);
        when(pptService.processPptAndGenerateContent(any()))
                .thenReturn("当前使用人数较多，AI 服务暂时繁忙，请稍后重试。");
        CoursewareProjectService service = new CoursewareProjectService(
                pptService, mock(TTSService.class));
        ReflectionTestUtils.setField(service, "coursewareDir", tempDirectory.toString());

        MockMultipartFile ppt = new MockMultipartFile("file", "失败示例.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.create(ppt, "alice"));
        assertTrue(exception.getMessage().contains("AI 服务暂时繁忙"));
    }

    private byte[] pptx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.createSlide();
            show.write(output);
        }
        return output.toByteArray();
    }
}

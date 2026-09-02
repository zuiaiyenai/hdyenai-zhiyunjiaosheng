package com.a09.tts.service;

import com.a09.tts.repository.CoursewareProjectRepository.ProjectData;
import com.a09.tts.repository.InMemoryCoursewareProjectRepository;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.service.CoursewareProjectService.DownloadArtifact;
import com.a09.tts.service.CoursewareProjectService.ProjectView;
import com.a09.tts.api.PageResult;
import com.a09.tts.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.poi.xslf.usermodel.XMLSlideShow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
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
        assertEquals("SUCCEEDED", created.status());
        assertThrows(ResourceNotFoundException.class, () -> service.get(created.id(), "bob"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.optimize(created.id(), "bob", "越权修改"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.updateScript(created.id(), "bob", "越权讲稿"));
        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "avatar.png", "image/png", png());
        assertThrows(ResourceNotFoundException.class,
                () -> service.uploadAvatar(created.id(), "bob", avatar));
        assertThrows(ResourceNotFoundException.class,
                () -> service.download(created.id(), "bob", "package"));

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

    @Test
    void reloadsProjectAndRevisionsFromRepositoryAfterServiceRestart() throws Exception {
        PPTService pptService = mock(PPTService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("第一版讲稿");
        InMemoryCoursewareProjectRepository repository = new InMemoryCoursewareProjectRepository();
        CoursewareProjectService first = service(pptService, repository);
        MockMultipartFile ppt = new MockMultipartFile("file", "重启恢复.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx());

        ProjectView created = first.create(ppt, "alice");
        first.updateScript(created.id(), "alice", "重启后的第二版讲稿");

        CoursewareProjectService restarted = service(pptService, repository);
        ProjectView restored = restarted.get(created.id(), "alice");

        assertEquals(1, restored.revision());
        assertEquals("重启后的第二版讲稿", restored.script());
        assertEquals("SUCCEEDED", restored.status());
        ProjectData stored = repository.findByIdAndOwner(created.id(), "alice").orElseThrow();
        assertTrue(!Path.of(stored.sourcePath()).isAbsolute());
        assertTrue(!Path.of(stored.outputPath()).isAbsolute());
        DownloadArtifact artifact = restarted.download(created.id(), "alice", "package");
        try (ZipFile zip = new ZipFile(artifact.path().toFile())) {
            assertNotNull(zip.getEntry("讲稿/历史版本-00.txt"));
            assertNotNull(zip.getEntry("讲稿/历史版本-01.txt"));
        }
    }

    @Test
    void rejectsPersistedPathTraversalDuringReload() {
        InMemoryCoursewareProjectRepository repository = new InMemoryCoursewareProjectRepository();
        Instant now = Instant.now();
        repository.save(new ProjectData(
                "unsafe", "alice", "非法项目", "SUCCEEDED",
                "../../outside.pptx", "alice/unsafe", "outside.pptx", "讲稿", 0,
                "longxiao", 1.0, 1.0, 1.0, null, null, null, null, now, now));
        CoursewareProjectService service = service(mock(PPTService.class), repository);

        assertThrows(IllegalArgumentException.class, () -> service.get("unsafe", "alice"));
    }

    @Test
    void paginatesProjectsWithinOwnerBoundary() throws Exception {
        PPTService pptService = mock(PPTService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("分页测试讲稿");
        InMemoryCoursewareProjectRepository repository = new InMemoryCoursewareProjectRepository();
        CoursewareProjectService service = service(pptService, repository);

        service.create(new MockMultipartFile("file", "alice-1.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx()), "alice");
        service.create(new MockMultipartFile("file", "bob.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx()), "bob");
        service.create(new MockMultipartFile("file", "alice-2.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation", pptx()), "alice");

        PageResult<ProjectView> first = service.list("alice", 0, 1);
        PageResult<ProjectView> second = service.list("alice", 1, 1);
        assertEquals(1, first.content().size());
        assertTrue(first.hasNext());
        assertEquals(1, second.content().size());
        assertTrue(!second.hasNext());
        assertTrue(first.content().stream().noneMatch(project -> project.title().equals("bob")));
        assertThrows(IllegalArgumentException.class, () -> service.list("alice", 0, 101));
    }

    private CoursewareProjectService service(PPTService pptService,
                                             InMemoryCoursewareProjectRepository repository) {
        CoursewareProjectService service = new CoursewareProjectService(
                pptService, mock(TTSService.class), new UploadSecurityService(), repository);
        ReflectionTestUtils.setField(service, "coursewareDir", tempDirectory.toString());
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(service, "ffprobePath", "ffprobe");
        return service;
    }

    private byte[] pptx() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XMLSlideShow show = new XMLSlideShow()) {
            show.createSlide();
            show.write(output);
        }
        return output.toByteArray();
    }

    private byte[] png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}

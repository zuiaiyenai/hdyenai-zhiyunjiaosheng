package com.a09.tts.service;

import com.a09.tts.repository.CoursewareProjectRepository.ProjectData;
import com.a09.tts.repository.CoursewareProjectRepository;
import com.a09.tts.repository.CoursewareProjectRepository.RevisionData;
import com.a09.tts.repository.InMemoryCoursewareProjectRepository;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.storage.InMemoryStoredObjectMetadataRepository;
import com.a09.tts.storage.LocalObjectStorageService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.a09.tts.service.CoursewareProjectService.DownloadArtifact;
import com.a09.tts.service.CoursewareProjectService.ProjectView;
import com.a09.tts.api.PageResult;
import com.a09.tts.api.ConflictException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoursewareProjectServiceTest {

    @TempDir
    Path tempDirectory;
    private final InMemoryStoredObjectMetadataRepository objectMetadata =
            new InMemoryStoredObjectMetadataRepository();

    @Test
    void preparesUploadThenGeneratesInitialScriptFromStoredWorkingCopy() throws Exception {
        PPTService pptService = mock(PPTService.class);
        when(pptService.processPptAndGenerateContent(
                any(Path.class), eq("异步课件.pptx"))).thenReturn("异步生成讲稿");
        CoursewareProjectService service = service(
                pptService, new InMemoryCoursewareProjectRepository());
        MockMultipartFile file = new MockMultipartFile(
                "file", "异步课件.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx());

        ProjectView prepared = service.prepare(file, "alice");
        ProjectView completed = service.processPrepared(prepared.id(), "alice");

        assertEquals("PENDING", prepared.status());
        assertEquals("SUCCEEDED", completed.status());
        assertEquals("异步生成讲稿", completed.script());
    }

    @Test
    void keepsScriptRevisionsScopesOwnerAndPackagesGeneratedAudio() throws Exception {
        PPTService pptService = mock(PPTService.class);
        TTSService ttsService = mock(TTSService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("第一版教学讲稿");
        when(pptService.optimizeCoursewareContent(anyString(), anyString()))
                .thenReturn("第二版教学讲稿，包含课堂提问");
        when(ttsService.tts(anyString(), eq("longxiao"), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(ResponseEntity.ok(new byte[]{82, 73, 70, 70}));

        CoursewareProjectService service = service(
                pptService, ttsService, new InMemoryCoursewareProjectRepository());

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
        assertTrue(artifact.resource().getFile().isFile());
        try (ZipFile zip = new ZipFile(artifact.resource().getFile())) {
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
        CoursewareProjectService service = service(
                pptService, mock(TTSService.class), new InMemoryCoursewareProjectRepository());

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
        try (ZipFile zip = new ZipFile(artifact.resource().getFile())) {
            assertNotNull(zip.getEntry("讲稿/历史版本-00.txt"));
            assertNotNull(zip.getEntry("讲稿/历史版本-01.txt"));
        }
    }

    @Test
    void reloadsFreshStateWrittenByAnotherServiceInstance() throws Exception {
        PPTService pptService = mock(PPTService.class);
        when(pptService.processPptAndGenerateContent(any())).thenReturn("第一版讲稿");
        InMemoryCoursewareProjectRepository repository = new InMemoryCoursewareProjectRepository();
        CoursewareProjectService first = service(pptService, repository);
        CoursewareProjectService second = service(pptService, repository);
        ProjectView created = first.create(new MockMultipartFile(
                "file", "双实例.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                pptx()), "alice");
        assertEquals("第一版讲稿", first.get(created.id(), "alice").script());

        second.updateScript(created.id(), "alice", "第二个实例写入的讲稿");

        assertEquals("第二个实例写入的讲稿", first.get(created.id(), "alice").script());
    }

    @Test
    void doesNotMarkAnotherInstancesProcessingProjectAsFailed() {
        InMemoryCoursewareProjectRepository repository = new InMemoryCoursewareProjectRepository();
        Instant now = Instant.now();
        repository.save(new ProjectData(
                "interrupted", "alice", "中断项目", "PROCESSING",
                "alice/interrupted/source.pptx", "alice/interrupted", "source.pptx", "讲稿", 0,
                "longxiao", 1.0, 1.0, 1.0, null, null, null, null, now, now));
        CoursewareProjectService restarted = service(mock(PPTService.class), repository);

        ProjectView restored = restarted.get("interrupted", "alice");

        assertEquals("PROCESSING", restored.status());
        assertEquals(null, restored.errorMessage());
        ProjectData stored = repository.findByIdAndOwner("interrupted", "alice").orElseThrow();
        assertEquals("PROCESSING", stored.status());
        ConflictException conflict = assertThrows(ConflictException.class,
                () -> restarted.updateScript("interrupted", "alice", "并发覆盖"));
        assertEquals("COURSEWARE_BUSY", conflict.errorCode());
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

    @Test
    void loadsListRevisionsWithOneBatchQuery() {
        CoursewareProjectRepository repository = mock(CoursewareProjectRepository.class);
        Instant now = Instant.now();
        ProjectData first = projectData("00000000-0000-0000-0000-000000000071", now);
        ProjectData second = projectData("00000000-0000-0000-0000-000000000072", now.minusSeconds(1));
        when(repository.findByOwner("alice", 0, 21)).thenReturn(List.of(first, second));
        when(repository.findRevisionsByProjectIds(anyList())).thenReturn(List.of(
                new RevisionData(first.projectId(), 0, "自动生成", "讲稿", now),
                new RevisionData(second.projectId(), 0, "自动生成", "讲稿", now)));
        CoursewareProjectService service = service(mock(PPTService.class), repository);

        PageResult<ProjectView> result = service.list("alice", 0, 20);

        assertEquals(2, result.content().size());
        verify(repository).findRevisionsByProjectIds(
                List.of(first.projectId(), second.projectId()));
        verify(repository, never()).findRevisions(anyString());
    }

    private CoursewareProjectService service(PPTService pptService,
                                             CoursewareProjectRepository repository) {
        return service(pptService, mock(TTSService.class), repository);
    }

    private CoursewareProjectService service(PPTService pptService, TTSService ttsService,
                                             CoursewareProjectRepository repository) {
        CoursewareProjectService service = new CoursewareProjectService(
                pptService, ttsService, new UploadSecurityService(), repository,
                new ManagedObjectStorageService(
                        new LocalObjectStorageService(tempDirectory.resolve("objects").toString()),
                        objectMetadata));
        ReflectionTestUtils.setField(service, "coursewareDir", tempDirectory.toString());
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(service, "ffprobePath", "ffprobe");
        return service;
    }

    private ProjectData projectData(String id, Instant updatedAt) {
        return new ProjectData(
                id, "alice", "容量课件", "SUCCEEDED",
                "courseware/alice/" + id + "/source.pptx",
                "courseware/alice/" + id, "source.pptx", "讲稿", 0,
                "longxiao", 1.0, 1.0, 1.0, null, null, null, null,
                updatedAt, updatedAt);
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

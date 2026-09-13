package com.a09.tts.task;

import com.a09.tts.service.ASRService;
import com.a09.tts.service.AccessibilityService;
import com.a09.tts.service.CoursewareProjectService;
import com.a09.tts.service.PPTService;
import com.a09.tts.service.SoundCloneService;
import com.a09.tts.service.SpeakingPracticeService;
import com.a09.tts.service.VideoVoiceSwapService;
import com.a09.tts.storage.ManagedObjectStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskWorkDispatcherTest {

    @Test
    void marksCoursewareFailedWhenTerminalTaskIsCleanedUp() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CoursewareProjectService coursewareService = mock(CoursewareProjectService.class);
        TaskWorkDispatcher dispatcher = new TaskWorkDispatcher(
                mock(ManagedObjectStorageService.class), mock(ASRService.class),
                mock(AccessibilityService.class), mock(VideoVoiceSwapService.class),
                mock(SoundCloneService.class), mock(SpeakingPracticeService.class),
                mock(PPTService.class), coursewareService, objectMapper,
                TaskResourceBulkheads.unrestricted());
        Instant now = Instant.now();
        TaskRecord task = new TaskRecord(
                "task-1", "alice", "COURSEWARE_AUDIO", TaskStatus.FAILED, 0,
                objectMapper.writeValueAsString(new TaskPayloads.CoursewareAudio(
                        "project-1", "longxiao", 1.0, 1.0, 1.0)),
                null, "TASK_EXECUTION_FAILED", "任务执行失败", null,
                1, 3, now, now, now, now, now, "worker-1", 1);

        dispatcher.cleanup(task);

        verify(coursewareService).failTask("project-1", "alice", "课件任务未完成");
    }
}

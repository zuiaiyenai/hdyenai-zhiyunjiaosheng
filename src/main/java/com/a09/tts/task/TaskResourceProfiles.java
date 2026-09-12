package com.a09.tts.task;

import java.util.Map;
import java.util.Set;

public final class TaskResourceProfiles {
    private static final Map<String, Set<TaskResource>> RESOURCES = Map.ofEntries(
            Map.entry("ASR_TRANSCRIBE", Set.of(TaskResource.ASR)),
            Map.entry("VOICE_NOTE", Set.of(TaskResource.ASR)),
            Map.entry("VIDEO_SUBTITLES", Set.of(TaskResource.ASR, TaskResource.FFMPEG)),
            Map.entry("VIDEO_VOICE_SWAP",
                    Set.of(TaskResource.ASR, TaskResource.TTS, TaskResource.FFMPEG)),
            Map.entry("SOUND_CLONE", Set.of(TaskResource.TTS)),
            Map.entry("SPEAKING_EVALUATION", Set.of(TaskResource.ASR)),
            Map.entry("PPT_SUMMARY", Set.of(TaskResource.COURSEWARE)),
            Map.entry("COURSEWARE_CREATE", Set.of(TaskResource.COURSEWARE)),
            Map.entry("COURSEWARE_OPTIMIZE", Set.of(TaskResource.COURSEWARE)),
            Map.entry("COURSEWARE_AUDIO",
                    Set.of(TaskResource.COURSEWARE, TaskResource.TTS)),
            Map.entry("COURSEWARE_VIDEO",
                    Set.of(TaskResource.COURSEWARE, TaskResource.FFMPEG)));

    private TaskResourceProfiles() {
    }

    public static Set<TaskResource> resourcesFor(String taskType) {
        Set<TaskResource> resources = RESOURCES.get(taskType);
        if (resources == null) {
            throw new IllegalArgumentException("未知任务类型: " + taskType);
        }
        return resources;
    }
}

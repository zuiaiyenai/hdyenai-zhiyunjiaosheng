package com.a09.tts.task;

public final class TaskPayloads {
    private TaskPayloads() {
    }

    public record Asr(String objectKey, String uploadId, String originalFilename,
                      String language) {
    }

    public record VoiceNote(String objectKey, String uploadId, String originalFilename,
                            String title, String noteId) {
    }

    public record VideoSubtitles(String objectKey, String uploadId, String originalFilename) {
    }

    public record VideoSwap(String objectKey, String uploadId, String originalFilename,
                            String voiceType, String transcript, String subtitles,
                            boolean includeSubtitles) {
    }

    public record SoundClone(String objectKey, String uploadId, String originalFilename,
                             String promptText, String promptLang, String text,
                             String textLang) {
    }

    public record SpeakingEvaluation(
            String objectKey, String uploadId, String originalFilename,
            String referenceText, String mode, String sessionId, String language) {
    }

    public record PptSummary(String objectKey, String uploadId, String originalFilename) {
    }

    public record CoursewareCreate(String projectId) {
    }

    public record CoursewareOptimize(String projectId, String instruction) {
    }

    public record CoursewareAudio(String projectId, String voice,
                                  double speed, double pitch, double rhythm) {
    }

    public record CoursewareVideo(String projectId) {
    }
}

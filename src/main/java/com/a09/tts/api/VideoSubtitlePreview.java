package com.a09.tts.api;

public record VideoSubtitlePreview(
        String transcript,
        String subtitles,
        double durationSeconds,
        String timingSource
) {
}

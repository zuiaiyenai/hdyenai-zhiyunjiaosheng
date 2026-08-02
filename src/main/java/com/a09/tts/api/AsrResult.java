package com.a09.tts.api;

import java.util.List;

public record AsrResult(
        String text,
        Double fluency,
        Double pronunciation,
        Double accuracy,
        List<Segment> segments
) {
    public record Segment(double startSeconds, double endSeconds, String text) {
    }
}

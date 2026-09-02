package com.a09.tts.service.impl;

import com.a09.tts.api.AsrResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVoiceSwapServiceImplTest {

    @Test
    void extractedAudioUsesFunAsrCompatiblePcmFormat() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);

        List<String> command = service.buildExtractCommand("input.webm", "audio.wav");

        assertTrue(contains(command, "-i", "input.webm", "-vn"), command.toString());
        assertTrue(contains(command, "-ar", "16000", "-ac", "1"), command.toString());
        assertTrue(contains(command, "-c:a", "pcm_s16le", "audio.wav"), command.toString());
    }

    @Test
    void mergeOptionsAreOutputOptionsAndReplaceTheOriginalAudio() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);

        List<String> command = service.buildMergeCommand(
                "input.mp4", "voice.wav", "subtitles.srt", "output.mp4", 9.6 / 5.28);

        assertTrue(command.indexOf("-vf") > command.lastIndexOf("-i"), command.toString());
        assertTrue(contains(command, "-map", "0:v:0", "-map", "1:a:0"), command.toString());
        assertTrue(contains(command, "-c:v", "libx264"), command.toString());
        assertTrue(contains(command, "-pix_fmt", "yuv420p"), command.toString());
        assertTrue(contains(command, "-filter:a", "atempo=1.818182"), command.toString());
        assertTrue(contains(command, "-c:a", "aac", "-shortest"), command.toString());
        assertTrue(contains(command, "-movflags", "+faststart"), command.toString());
    }

    @Test
    void mergeCanKeepVideoWithoutBurnedSubtitles() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);

        List<String> command = service.buildMergeCommand(
                "input.mp4", "voice.wav", null, "output.mp4", 1.0);

        assertFalse(command.contains("-vf"), command.toString());
        assertTrue(contains(command, "-map", "0:v:0", "-map", "1:a:0"), command.toString());
    }

    @Test
    void subtitlesUseAsrSegmentTimestampsWhenAvailable() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);
        AsrResult result = new AsrResult("你好。欢迎使用。",
                null, null, null, List.of(
                new AsrResult.Segment(0.25, 1.5, "你好。"),
                new AsrResult.Segment(1.8, 3.25, "欢迎使用。")));

        String subtitles = service.buildSrt(result, 4.0);

        assertEquals("""
                1
                00:00:00,250 --> 00:00:01,500
                你好。

                2
                00:00:01,800 --> 00:00:03,250
                欢迎使用。

                """, subtitles);
    }

    @Test
    void subtitlesFallBackToWholeVideoTimelineWithoutSegments() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);
        AsrResult result = new AsrResult("第一句。第二句。", null, null, null, List.of());

        String subtitles = service.buildSrt(result, 6.0);

        assertTrue(subtitles.contains("00:00:00,000 --> 00:00:03,000"), subtitles);
        assertTrue(subtitles.contains("00:00:03,000 --> 00:00:06,000"), subtitles);
    }

    @Test
    void tempoFilterSupportsRatiosOutsideOneAtempoRange() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);

        assertTrue(service.buildAtempoFilter(4.0)
                .equals("atempo=2.000000,atempo=2.000000"));
        assertTrue(service.buildAtempoFilter(0.25)
                .equals("atempo=0.500000,atempo=0.500000"));
    }


    private boolean contains(List<String> command, String... expected) {
        for (int start = 0; start <= command.size() - expected.length; start++) {
            boolean match = true;
            for (int offset = 0; offset < expected.length; offset++) {
                if (!expected[offset].equals(command.get(start + offset))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}

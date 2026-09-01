package com.a09.tts.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoVoiceSwapServiceImplTest {

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

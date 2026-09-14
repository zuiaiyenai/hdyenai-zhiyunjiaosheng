package com.a09.tts.service.impl;

import com.a09.tts.api.AsrResult;
import com.a09.tts.media.ExternalProcessRunner;
import com.a09.tts.service.TTSService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoVoiceSwapServiceImplTest {
    @TempDir
    Path root;


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
                "input.mp4", "voice.wav", "subtitles.srt", "output.mp4", 9.6 / 5.28, 5.28);

        assertTrue(command.indexOf("-vf") > command.lastIndexOf("-i"), command.toString());
        assertTrue(contains(command, "-map", "0:v:0", "-map", "1:a:0"), command.toString());
        assertTrue(contains(command, "-c:v", "libx264"), command.toString());
        assertTrue(contains(command, "-pix_fmt", "yuv420p"), command.toString());
        assertTrue(contains(command, "-filter:a", "atempo=1.818182,apad"), command.toString());
        assertTrue(contains(command, "-c:a", "aac", "-t", "5.280000"), command.toString());
        assertFalse(command.contains("-shortest"), command.toString());
        assertTrue(contains(command, "-movflags", "+faststart"), command.toString());
    }

    @Test
    void mergeCanKeepVideoWithoutBurnedSubtitles() {
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, null);

        List<String> command = service.buildMergeCommand(
                "input.mp4", "voice.wav", null, "output.mp4", 1.0, 5.28);

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

    @Test
    void streamsSynthesizedAudioAndWritesVideoToCallerOwnedPath() throws Exception {
        TTSService tts = mock(TTSService.class);
        doAnswer(invocation -> {
            OutputStream output = invocation.getArgument(5);
            output.write(new byte[]{1, 2, 3});
            return null;
        }).when(tts).stream(anyString(), anyString(), anyDouble(), anyDouble(), anyDouble(), any());
        ExternalProcessRunner runner = mock(ExternalProcessRunner.class);
        when(runner.run(anyList(), anyString())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.contains("-show_entries")) {
                return new ExternalProcessRunner.ProcessResult(0, "1.0");
            }
            Files.write(Path.of(command.get(command.size() - 1)), new byte[]{4, 5, 6});
            return new ExternalProcessRunner.ProcessResult(0, "");
        });
        VideoVoiceSwapServiceImpl service = new VideoVoiceSwapServiceImpl(null, tts, runner);
        ReflectionTestUtils.setField(service, "outputDir", root.toString());
        ReflectionTestUtils.setField(service, "ffmpegPath", "ffmpeg");
        ReflectionTestUtils.setField(service, "ffprobePath", "ffprobe");
        Path output = root.resolve("result.mp4");

        service.processVideo("input.mp4", "voice", 1.1, 0.9, 1.2,
                "已校对文本", null, false, output);

        assertEquals(3, Files.size(output));
        verify(tts).stream(eq("已校对文本"), eq("voice"), eq(1.1), eq(0.9), eq(1.2),
                org.mockito.ArgumentMatchers.any(OutputStream.class));
        try (var files = Files.list(root)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("video-")));
        }
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

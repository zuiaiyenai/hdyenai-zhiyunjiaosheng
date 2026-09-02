package com.a09.tts.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadUtilsSecurityTest {

    @TempDir
    Path uploadRoot;

    @ParameterizedTest
    @ValueSource(strings = {
            "../secret.wav",
            "../../secret.wav",
            "..\\secret.wav",
            "%2e%2e/secret.wav",
            "%252e%252e%252fsecret.wav",
            "C:\\Windows\\win.ini",
            "/etc/passwd"
    })
    void rejectsTraversalAndCrossPlatformAbsolutePaths(String storedPath) {
        assertThrows(IllegalArgumentException.class,
                () -> UploadUtils.resolveWithin(uploadRoot, storedPath));
    }

    @Test
    void acceptsRelativeAndAbsolutePathsInsideUploadRoot() throws Exception {
        Path audio = Files.write(uploadRoot.resolve("voice.wav"), new byte[]{1, 2, 3});

        assertEquals(audio, UploadUtils.resolveWithin(uploadRoot, "voice.wav"));
        assertEquals(audio, UploadUtils.resolveWithin(uploadRoot, audio.toString()));
    }

    @Test
    void refusesToDeleteFileOutsideUploadRoot() throws Exception {
        Path nestedRoot = Files.createDirectory(uploadRoot.resolve("voices"));
        Path outside = Files.write(uploadRoot.resolve("outside.wav"), new byte[]{9});

        assertThrows(IllegalArgumentException.class,
                () -> UploadUtils.deleteWithin(nestedRoot, outside.toString()));
        assertTrue(Files.exists(outside));
    }
}

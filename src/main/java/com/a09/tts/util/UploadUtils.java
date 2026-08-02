package com.a09.tts.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class UploadUtils {
    private UploadUtils() {
    }

    public static Path save(MultipartFile file, Path directory, Set<String> extensions) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalName = file.getOriginalFilename();
        String safeName = originalName == null ? "upload.bin" : Path.of(originalName).getFileName().toString();
        String extension = extensionOf(safeName);
        if (!extensions.isEmpty() && !extensions.contains(extension)) {
            throw new IllegalArgumentException("不支持的文件类型: " + extension);
        }
        Files.createDirectories(directory);
        Path root = directory.toAbsolutePath().normalize();
        Path target = root.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件名");
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
    }
}

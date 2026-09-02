package com.a09.tts.util;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class UploadUtils {
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:[\\\\/].*");

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
        Path root = directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(UUID.randomUUID() + extension).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法文件名");
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static Path resolveWithin(Path directory, String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        Path root = directory.toAbsolutePath().normalize();
        String decodedPath = decodePath(storedPath);
        String portablePath = decodedPath.replace('\\', '/');
        if (portablePath.indexOf('\0') >= 0 || containsParentSegment(portablePath)) {
            throw new IllegalArgumentException("非法文件路径");
        }

        Path supplied = Path.of(decodedPath);
        if (!supplied.isAbsolute()
                && (WINDOWS_ABSOLUTE_PATH.matcher(decodedPath).matches() || portablePath.startsWith("/"))) {
            throw new IllegalArgumentException("非法绝对路径");
        }
        Path target = supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("文件路径超出上传目录");
        }
        return target;
    }

    public static boolean deleteWithin(Path directory, String storedPath) throws IOException {
        return Files.deleteIfExists(resolveWithin(directory, storedPath));
    }

    public static String extensionOf(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index).toLowerCase(Locale.ROOT);
    }

    private static String decodePath(String path) {
        String decoded = path;
        for (int i = 0; i < 2; i++) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                break;
            }
            decoded = next;
        }
        return decoded;
    }

    private static boolean containsParentSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}

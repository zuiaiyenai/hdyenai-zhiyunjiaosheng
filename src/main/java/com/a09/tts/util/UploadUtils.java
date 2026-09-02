package com.a09.tts.util;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

public final class UploadUtils {
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[a-zA-Z]:[\\\\/].*");

    private UploadUtils() {
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
        if (Files.exists(root) && Files.exists(target)) {
            try {
                if (!target.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException("文件路径超出上传目录");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("无法校验文件路径", exception);
            }
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

package com.a09.tts.security;

import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioSystem;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class UploadSecurityService {
    public enum Type { AUDIO, IMAGE, PRESENTATION, VIDEO, TEXT }

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(".wav", ".mp3", ".m4a", ".flac", ".ogg", ".webm");
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".gif");
    private static final Set<String> PRESENTATION_EXTENSIONS = Set.of(".ppt", ".pptx");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".mov", ".webm", ".avi");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".txt", ".md");

    @Value("${app.upload.audio-max-size:20MB}")
    private org.springframework.util.unit.DataSize audioMaxSize = org.springframework.util.unit.DataSize.ofMegabytes(20);
    @Value("${app.upload.image-max-size:10MB}")
    private org.springframework.util.unit.DataSize imageMaxSize = org.springframework.util.unit.DataSize.ofMegabytes(10);
    @Value("${app.upload.presentation-max-size:30MB}")
    private org.springframework.util.unit.DataSize presentationMaxSize = org.springframework.util.unit.DataSize.ofMegabytes(30);
    @Value("${app.upload.video-max-size:50MB}")
    private org.springframework.util.unit.DataSize videoMaxSize = org.springframework.util.unit.DataSize.ofMegabytes(50);
    @Value("${app.upload.text-max-size:2MB}")
    private org.springframework.util.unit.DataSize textMaxSize = org.springframework.util.unit.DataSize.ofMegabytes(2);
    @Value("${app.upload.user-quota:500MB}")
    private org.springframework.util.unit.DataSize userQuota = org.springframework.util.unit.DataSize.ofMegabytes(500);
    @Value("${app.upload.image-max-width:4096}")
    private int imageMaxWidth = 4096;
    @Value("${app.upload.image-max-height:4096}")
    private int imageMaxHeight = 4096;
    @Value("${app.upload.presentation-max-slides:200}")
    private int presentationMaxSlides = 200;
    @Value("${app.upload.audio-max-duration-seconds:1800}")
    private long audioMaxDurationSeconds = 1800;

    public void validate(MultipartFile file, Type type) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String originalName = safeOriginalName(file.getOriginalFilename());
        String extension = extensionOf(originalName);
        if (!extensions(type).contains(extension)) {
            throw new IllegalArgumentException("不支持的文件扩展名");
        }
        if (file.getSize() > maxBytes(type)) {
            throw new IllegalArgumentException("上传文件超过大小限制");
        }
        if (!mimeAllowed(file.getContentType(), type)) {
            throw new IllegalArgumentException("上传文件 MIME 类型不受支持");
        }
        byte[] header;
        try (InputStream input = file.getInputStream()) {
            header = input.readNBytes(16);
        }
        if (!magicMatches(header, extension, type)) {
            throw new IllegalArgumentException("上传文件内容与声明类型不匹配");
        }
        switch (type) {
            case IMAGE -> validateImage(file);
            case PRESENTATION -> validatePresentation(file, extension);
            case AUDIO -> validateWaveDuration(file, extension);
            case TEXT -> validateText(file);
            case VIDEO -> { }
        }
    }

    public Path save(MultipartFile file, Path rootDirectory, Type type, String owner) throws IOException {
        validate(file, type);
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path ownerDirectory = root.resolve(ownerKey(owner)).normalize();
        if (!ownerDirectory.startsWith(root)) {
            throw new IllegalArgumentException("上传目录非法");
        }
        Files.createDirectories(ownerDirectory);
        Path realRoot = root.toRealPath();
        Path realOwnerDirectory = ownerDirectory.toRealPath();
        if (!realOwnerDirectory.startsWith(realRoot)) {
            throw new IllegalArgumentException("上传目录超出根目录");
        }
        ensureQuota(ownerDirectory, file.getSize());
        Path target = ownerDirectory.resolve(UUID.randomUUID() + extensionOf(safeOriginalName(file.getOriginalFilename())))
                .normalize();
        if (!target.startsWith(ownerDirectory)) {
            throw new IllegalArgumentException("上传路径非法");
        }
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public Path ownerDirectory(Path rootDirectory, String owner) throws IOException {
        Path root = rootDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path ownerDirectory = root.resolve(ownerKey(owner)).normalize();
        if (!ownerDirectory.startsWith(root)) {
            throw new IllegalArgumentException("上传目录非法");
        }
        Files.createDirectories(ownerDirectory);
        if (!ownerDirectory.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("上传目录超出根目录");
        }
        return ownerDirectory;
    }

    public boolean delete(Path rootDirectory, Path target) throws IOException {
        if (target == null) {
            return false;
        }
        Path root = rootDirectory.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(root)) {
            throw new IllegalArgumentException("删除路径超出上传目录");
        }
        if (Files.exists(normalizedTarget)
                && !normalizedTarget.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("删除路径超出上传目录");
        }
        return Files.deleteIfExists(normalizedTarget);
    }

    public void ensureQuota(Path ownerDirectory, long additionalBytes) throws IOException {
        long used;
        try (var files = Files.walk(ownerDirectory)) {
            used = files.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException exception) { return 0L; }
            }).sum();
        }
        if (additionalBytes > userQuota.toBytes() - used) {
            throw new IllegalArgumentException("用户上传空间配额不足");
        }
    }

    private void validateImage(MultipartFile file) throws IOException {
        BufferedImage image;
        try (InputStream input = file.getInputStream()) {
            image = ImageIO.read(input);
        }
        if (image == null) {
            throw new IllegalArgumentException("无法解码图片文件");
        }
        if (image.getWidth() > imageMaxWidth || image.getHeight() > imageMaxHeight) {
            throw new IllegalArgumentException("图片尺寸超过限制");
        }
    }

    private void validatePresentation(MultipartFile file, String extension) throws IOException {
        try (InputStream raw = file.getInputStream(); BufferedInputStream input = new BufferedInputStream(raw)) {
            int slides;
            if (".pptx".equals(extension)) {
                try (XMLSlideShow show = new XMLSlideShow(input)) { slides = show.getSlides().size(); }
            } else {
                try (HSLFSlideShow show = new HSLFSlideShow(input)) { slides = show.getSlides().size(); }
            }
            if (slides > presentationMaxSlides) {
                throw new IllegalArgumentException("PPT 页数超过限制");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解析 PPT 文件");
        }
    }

    private void validateWaveDuration(MultipartFile file, String extension) throws IOException {
        if (!".wav".equals(extension)) return;
        try (var stream = AudioSystem.getAudioInputStream(new BufferedInputStream(file.getInputStream()))) {
            long frames = stream.getFrameLength();
            float rate = stream.getFormat().getFrameRate();
            if (frames > 0 && rate > 0 && frames / rate > audioMaxDurationSeconds) {
                throw new IllegalArgumentException("音频时长超过限制");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法解码 WAV 音频");
        }
    }

    private void validateText(MultipartFile file) throws IOException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(file.getBytes()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码");
        }
    }

    private boolean magicMatches(byte[] h, String extension, Type type) {
        if (type == Type.TEXT) return true;
        if (h.length < 4) return false;
        return switch (extension) {
            case ".wav" -> ascii(h, 0, "RIFF") && h.length >= 12 && ascii(h, 8, "WAVE");
            case ".mp3" -> ascii(h, 0, "ID3") || (u(h[0]) == 0xff && (u(h[1]) & 0xe0) == 0xe0);
            case ".flac" -> ascii(h, 0, "fLaC");
            case ".ogg" -> ascii(h, 0, "OggS");
            case ".m4a", ".mp4", ".mov" -> h.length >= 12 && ascii(h, 4, "ftyp");
            case ".webm" -> u(h[0]) == 0x1a && u(h[1]) == 0x45 && u(h[2]) == 0xdf && u(h[3]) == 0xa3;
            case ".avi" -> ascii(h, 0, "RIFF") && h.length >= 12 && ascii(h, 8, "AVI ");
            case ".jpg", ".jpeg" -> u(h[0]) == 0xff && u(h[1]) == 0xd8 && u(h[2]) == 0xff;
            case ".png" -> u(h[0]) == 0x89 && ascii(h, 1, "PNG");
            case ".gif" -> ascii(h, 0, "GIF8");
            case ".pptx" -> u(h[0]) == 0x50 && u(h[1]) == 0x4b;
            case ".ppt" -> u(h[0]) == 0xd0 && u(h[1]) == 0xcf && u(h[2]) == 0x11 && u(h[3]) == 0xe0;
            default -> false;
        };
    }

    private boolean mimeAllowed(String mime, Type type) {
        if (mime == null) return false;
        String value = mime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        return switch (type) {
            case AUDIO -> value.startsWith("audio/") || value.equals("video/webm");
            case IMAGE -> value.startsWith("image/");
            case PRESENTATION -> value.equals("application/vnd.ms-powerpoint")
                    || value.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation");
            case VIDEO -> value.startsWith("video/");
            case TEXT -> value.equals("text/plain") || value.equals("text/markdown");
        };
    }

    private Set<String> extensions(Type type) {
        return switch (type) {
            case AUDIO -> AUDIO_EXTENSIONS;
            case IMAGE -> IMAGE_EXTENSIONS;
            case PRESENTATION -> PRESENTATION_EXTENSIONS;
            case VIDEO -> VIDEO_EXTENSIONS;
            case TEXT -> TEXT_EXTENSIONS;
        };
    }

    private long maxBytes(Type type) {
        return switch (type) {
            case AUDIO -> audioMaxSize.toBytes();
            case IMAGE -> imageMaxSize.toBytes();
            case PRESENTATION -> presentationMaxSize.toBytes();
            case VIDEO -> videoMaxSize.toBytes();
            case TEXT -> textMaxSize.toBytes();
        };
    }

    private String safeOriginalName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("非法上传文件名");
        }
        String decoded = originalName;
        for (int i = 0; i < 2; i++) {
            decoded = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
        }
        if (decoded.indexOf('\0') >= 0 || decoded.contains("/") || decoded.contains("\\")
                || decoded.equals("..") || decoded.startsWith("..")
                || decoded.matches("^[a-zA-Z]:.*")) {
            throw new IllegalArgumentException("非法上传文件名");
        }
        return decoded;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String ownerKey(String owner) {
        try {
            String normalized = owner == null || owner.isBlank() ? "anonymous" : owner.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建上传目录键", exception);
        }
    }

    private boolean ascii(byte[] bytes, int offset, String expected) {
        if (bytes.length < offset + expected.length()) return false;
        for (int i = 0; i < expected.length(); i++) {
            if (bytes[offset + i] != (byte) expected.charAt(i)) return false;
        }
        return true;
    }

    private int u(byte value) { return value & 0xff; }
}

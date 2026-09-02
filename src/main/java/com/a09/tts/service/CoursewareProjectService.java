package com.a09.tts.service;

import com.a09.tts.media.ExternalProcessRunner;
import com.a09.tts.security.UploadSecurityService;
import com.a09.tts.security.UploadSecurityService.Type;
import com.a09.tts.repository.CoursewareProjectRepository;
import com.a09.tts.repository.CoursewareProjectRepository.ProjectData;
import com.a09.tts.repository.CoursewareProjectRepository.RevisionData;
import com.a09.tts.repository.InMemoryCoursewareProjectRepository;
import com.a09.tts.util.UploadUtils;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class CoursewareProjectService {

    private static final int MAX_SCRIPT_LENGTH = 50000;
    private static final int TTS_CHUNK_LENGTH = 4500;
    private final Map<String, ProjectState> projects = new ConcurrentHashMap<>();
    private final PPTService pptService;
    private final TTSService ttsService;
    private final UploadSecurityService uploadSecurity;
    private final CoursewareProjectRepository projectRepository;
    private final ExternalProcessRunner processRunner;

    @Value("${app.courseware-dir:./uploads/courseware}")
    private String coursewareDir;

    @Value("${app.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${app.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Autowired
    public CoursewareProjectService(PPTService pptService, TTSService ttsService,
                                    UploadSecurityService uploadSecurity,
                                    CoursewareProjectRepository projectRepository,
                                    ExternalProcessRunner processRunner) {
        this.pptService = pptService;
        this.ttsService = ttsService;
        this.uploadSecurity = uploadSecurity;
        this.projectRepository = projectRepository;
        this.processRunner = processRunner;
    }

    public CoursewareProjectService(PPTService pptService, TTSService ttsService,
                                    UploadSecurityService uploadSecurity,
                                    CoursewareProjectRepository projectRepository) {
        this(pptService, ttsService, uploadSecurity, projectRepository,
                new ExternalProcessRunner(Duration.ofMinutes(10)));
    }

    public CoursewareProjectService(PPTService pptService, TTSService ttsService,
                                    UploadSecurityService uploadSecurity) {
        this(pptService, ttsService, uploadSecurity, new InMemoryCoursewareProjectRepository());
    }

    public CoursewareProjectService(PPTService pptService, TTSService ttsService) {
        this(pptService, ttsService, new UploadSecurityService());
    }

    public ProjectView create(MultipartFile file, String owner) throws IOException {
        uploadSecurity.validate(file, Type.PRESENTATION);
        String fileName = validatePpt(file);
        String extension = fileName.toLowerCase(Locale.ROOT).endsWith(".pptx") ? ".pptx" : ".ppt";
        String id = UUID.randomUUID().toString();
        Path ownerDirectory = uploadSecurity.ownerDirectory(projectRoot(), owner);
        uploadSecurity.ensureQuota(ownerDirectory, file.getSize());
        Path directory = ownerDirectory.resolve(id).normalize();
        if (!directory.startsWith(ownerDirectory)) {
            throw new IllegalArgumentException("非法课件项目路径");
        }
        Files.createDirectories(directory);
        Path source = directory.resolve("source" + extension);
        Files.copy(file.getInputStream(), source, StandardCopyOption.REPLACE_EXISTING);

        ProjectState state = new ProjectState(id, normalizeOwner(owner), stripExtension(fileName),
                fileName, directory, source, "");
        projects.put(id, state);
        persist(state);
        begin(state);
        try {
            String script = pptService.processPptAndGenerateContent(file);
            if (script.startsWith("课件生成失败") || script.contains("AI 服务暂时繁忙")) {
                throw new IllegalStateException(script);
            }
            validateScript(script);
            state.script = script;
            Files.writeString(directory.resolve("script.txt"), script, StandardCharsets.UTF_8);
            Revision revision = new Revision(0, "根据 PPT 自动生成", script, Instant.now());
            state.revisions.add(revision);
            persistRevision(state, revision);
            succeed(state);
            return view(state);
        } catch (IOException | RuntimeException exception) {
            fail(state, exception);
            throw exception;
        }
    }

    public ProjectView get(String id, String owner) {
        return view(requireProject(id, owner));
    }

    public ProjectView optimize(String id, String owner, String instruction) throws IOException {
        if (instruction == null || instruction.isBlank() || instruction.length() > 1000) {
            throw new IllegalArgumentException("调整要求长度必须在 1 到 1000 字之间");
        }
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            begin(state);
            try {
                String optimized = pptService.optimizeCoursewareContent(state.script, instruction.trim());
                validateScript(optimized);
                state.script = optimized;
                state.revision++;
                state.audio = null;
                state.video = null;
                Revision revision = new Revision(state.revision, instruction.trim(), optimized, Instant.now());
                state.revisions.add(revision);
                saveScript(state);
                persistRevision(state, revision);
                succeed(state);
                return view(state);
            } catch (IOException | RuntimeException exception) {
                fail(state, exception);
                throw exception;
            }
        }
    }

    public ProjectView updateScript(String id, String owner, String script) throws IOException {
        validateScript(script);
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            begin(state);
            try {
                state.script = script.trim();
                state.revision++;
                state.audio = null;
                state.video = null;
                Revision revision = new Revision(
                        state.revision, "用户手动修改", state.script, Instant.now());
                state.revisions.add(revision);
                saveScript(state);
                persistRevision(state, revision);
                succeed(state);
                return view(state);
            } catch (IOException | RuntimeException exception) {
                fail(state, exception);
                throw exception;
            }
        }
    }

    public ProjectView generateAudio(String id, String owner, String voice,
                                     double speed, double pitch, double rhythm) throws IOException {
        validateSpeechSettings(voice, speed, pitch, rhythm);
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            begin(state);
            try {
                List<String> chunks = splitScript(state.script);
                List<Path> parts = new ArrayList<>();
                for (int index = 0; index < chunks.size(); index++) {
                    ResponseEntity<byte[]> response = ttsService.tts(
                            chunks.get(index), voice, speed, pitch, rhythm);
                    byte[] audio = response.getBody();
                    if (audio == null || audio.length == 0) {
                        throw new IOException("语音服务未返回音频");
                    }
                    Path part = state.directory.resolve(String.format("narration-%03d.wav", index + 1));
                    Files.write(part, audio);
                    parts.add(part);
                }
                state.audio = parts.size() == 1 ? parts.get(0) : concatAudio(state, parts);
                state.voice = voice;
                state.speed = speed;
                state.pitch = pitch;
                state.rhythm = rhythm;
                state.video = null;
                succeed(state);
                return view(state);
            } catch (IOException | RuntimeException exception) {
                fail(state, exception);
                throw exception;
            }
        }
    }

    public ProjectView uploadAvatar(String id, String owner, MultipartFile avatar) throws IOException {
        uploadSecurity.validate(avatar, Type.IMAGE);
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            begin(state);
            try {
                uploadSecurity.ensureQuota(state.directory.getParent(), avatar.getSize());
                Path avatarPath = state.directory.resolve("virtual-teacher.png");
                try (InputStream input = avatar.getInputStream()) {
                    BufferedImage image = ImageIO.read(input);
                    ImageIO.write(image, "png", avatarPath.toFile());
                }
                state.avatar = avatarPath;
                state.video = null;
                succeed(state);
                return view(state);
            } catch (IOException | RuntimeException exception) {
                fail(state, exception);
                throw exception;
            }
        }
    }

    public ProjectView generateVideo(String id, String owner) throws IOException {
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            begin(state);
            try {
                if (state.audio == null || !Files.isRegularFile(state.audio)) {
                    throw new IllegalStateException("请先生成讲稿语音");
                }
                List<Path> slides = renderSlides(state);
                double audioDuration = probeDuration(state.audio);
                double slideDuration = Math.max(1.0, audioDuration / slides.size());
                Path concat = state.directory.resolve("slides.txt");
                List<String> concatLines = new ArrayList<>();
                for (Path slide : slides) {
                    concatLines.add("file '" + ffmpegPath(slide) + "'");
                    concatLines.add("duration " + slideDuration);
                }
                concatLines.add("file '" + ffmpegPath(slides.get(slides.size() - 1)) + "'");
                Files.write(concat, concatLines, StandardCharsets.UTF_8);

                Path output = state.directory.resolve("recorded-course.mp4");
                List<String> command = new ArrayList<>(List.of(
                        ffmpegPath, "-hide_banner", "-y",
                        "-f", "concat", "-safe", "0", "-i", concat.toString(),
                        "-i", state.audio.toString()));
                if (state.avatar != null && Files.isRegularFile(state.avatar)) {
                    command.addAll(List.of("-loop", "1", "-i", state.avatar.toString(),
                            "-filter_complex",
                            "[0:v]scale=1280:720:force_original_aspect_ratio=decrease,"
                                    + "pad=1280:720:(ow-iw)/2:(oh-ih)/2:white[slide];"
                                    + "[2:v]scale=220:-1[teacher];"
                                    + "[slide][teacher]overlay=W-w-36:H-h-24[v]",
                            "-map", "[v]", "-map", "1:a:0"));
                } else {
                    command.addAll(List.of("-map", "0:v:0", "-map", "1:a:0"));
                }
                command.addAll(List.of("-r", "25", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-shortest", output.toString()));
                run(command, "录播课程生成失败");
                state.video = output;
                succeed(state);
                return view(state);
            } catch (IOException | RuntimeException exception) {
                fail(state, exception);
                throw exception;
            }
        }
    }

    public DownloadArtifact download(String id, String owner, String artifact) throws IOException {
        ProjectState state = requireProject(id, owner);
        synchronized (state) {
            return switch (artifact) {
                case "audio" -> existing(state.audio, "narration.wav", "audio/wav", "请先生成讲稿语音");
                case "video" -> existing(state.video, "recorded-course.mp4", "video/mp4", "请先生成录播课程");
                case "package" -> new DownloadArtifact(buildPackage(state),
                        safeFileName(state.title) + "-课件材料.zip", "application/zip");
                default -> throw new IllegalArgumentException("不支持的下载类型");
            };
        }
    }

    private Path concatAudio(ProjectState state, List<Path> parts) throws IOException {
        Path list = state.directory.resolve("audio-parts.txt");
        List<String> lines = parts.stream().map(path -> "file '" + ffmpegPath(path) + "'").toList();
        Files.write(list, lines, StandardCharsets.UTF_8);
        Path output = state.directory.resolve("narration.wav");
        run(List.of(ffmpegPath, "-hide_banner", "-y", "-f", "concat", "-safe", "0",
                "-i", list.toString(), "-c", "copy", output.toString()), "长讲稿音频合并失败");
        return output;
    }

    private List<Path> renderSlides(ProjectState state) throws IOException {
        Path slidesDirectory = state.directory.resolve("slides");
        Files.createDirectories(slidesDirectory);
        try (InputStream input = Files.newInputStream(state.source)) {
            if (state.source.getFileName().toString().endsWith(".pptx")) {
                try (XMLSlideShow show = new XMLSlideShow(input)) {
                    return renderXslf(show, slidesDirectory);
                }
            }
            try (HSLFSlideShow show = new HSLFSlideShow(input)) {
                return renderHslf(show, slidesDirectory);
            }
        }
    }

    private List<Path> renderXslf(XMLSlideShow show, Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        Dimension size = show.getPageSize();
        int index = 1;
        for (XSLFSlide slide : show.getSlides()) {
            paths.add(renderSlide(size, directory, index++, slide::draw));
        }
        return requireSlides(paths);
    }

    private List<Path> renderHslf(HSLFSlideShow show, Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        Dimension size = show.getPageSize();
        int index = 1;
        for (HSLFSlide slide : show.getSlides()) {
            paths.add(renderSlide(size, directory, index++, slide::draw));
        }
        return requireSlides(paths);
    }

    private Path renderSlide(Dimension sourceSize, Path directory, int index,
                             SlidePainter painter) throws IOException {
        int width = 1280;
        int height = 720;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.scale((double) width / sourceSize.width, (double) height / sourceSize.height);
            painter.paint(graphics);
        } finally {
            graphics.dispose();
        }
        Path output = directory.resolve(String.format("slide-%03d.png", index));
        ImageIO.write(image, "png", output.toFile());
        return output;
    }

    private List<Path> requireSlides(List<Path> slides) {
        if (slides.isEmpty()) {
            throw new IllegalArgumentException("PPT 中没有可生成录播的幻灯片");
        }
        return slides;
    }

    private double probeDuration(Path audio) throws IOException {
        String output = run(List.of(ffprobePath, "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", audio.toString()), "无法读取音频时长");
        try {
            return Double.parseDouble(output.trim());
        } catch (NumberFormatException exception) {
            throw new IOException("无法解析音频时长", exception);
        }
    }

    private String run(List<String> command, String message) throws IOException {
        return processRunner.run(command, message).output();
    }

    private Path buildPackage(ProjectState state) throws IOException {
        Path zip = state.directory.resolve("courseware-package.zip");
        try (ZipOutputStream output = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zip)), StandardCharsets.UTF_8)) {
            addZipEntry(output, state.source, state.fileName);
            addTextEntry(output, "讲稿/当前讲稿.txt", state.script);
            for (Revision revision : state.revisions) {
                addTextEntry(output, String.format("讲稿/历史版本-%02d.txt", revision.number()),
                        "调整要求：" + revision.instruction() + "\n时间：" + revision.createdAt()
                                + "\n\n" + revision.script());
            }
            if (state.audio != null && Files.isRegularFile(state.audio)) {
                addZipEntry(output, state.audio, "媒体/讲稿语音.wav");
            }
            if (state.video != null && Files.isRegularFile(state.video)) {
                addZipEntry(output, state.video, "媒体/录播课程.mp4");
            }
            if (state.avatar != null && Files.isRegularFile(state.avatar)) {
                addZipEntry(output, state.avatar, "媒体/虚拟教师.png");
            }
        }
        return zip;
    }

    private void addZipEntry(ZipOutputStream output, Path file, String name) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private void addTextEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private DownloadArtifact existing(Path path, String name, String type, String error) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException(error);
        }
        return new DownloadArtifact(path, name, type);
    }

    private ProjectState requireProject(String id, String owner) {
        String normalizedOwner = normalizeOwner(owner);
        ProjectState state = projects.get(id);
        if (state == null) {
            state = projectRepository.findByIdAndOwner(id, normalizedOwner)
                    .map(this::restore)
                    .orElse(null);
            if (state != null) {
                ProjectState existing = projects.putIfAbsent(id, state);
                if (existing != null) {
                    state = existing;
                }
            }
        }
        if (state == null || !state.owner.equals(normalizedOwner)) {
            throw new IllegalArgumentException("课件项目不存在或无权访问");
        }
        return state;
    }

    private ProjectView view(ProjectState state) {
        return new ProjectView(state.id, state.title, state.fileName, state.script, state.revision,
                state.voice, state.speed, state.pitch, state.rhythm,
                state.audio != null, state.video != null, state.avatar != null,
                state.status, state.errorMessage, state.createdAt, state.updatedAt);
    }

    private void persist(ProjectState state) {
        projectRepository.save(new ProjectData(
                state.id, state.owner, state.title, state.status,
                relativeKey(state.source), relativeKey(state.directory), state.fileName,
                state.script, state.revision, state.voice, state.speed, state.pitch, state.rhythm,
                relativeKey(state.audio), relativeKey(state.video), relativeKey(state.avatar),
                state.errorMessage, state.createdAt, state.updatedAt));
    }

    private void persistRevision(ProjectState state, Revision revision) {
        projectRepository.saveRevision(new RevisionData(
                state.id, revision.number(), revision.instruction(), revision.script(),
                revision.createdAt()));
    }

    private ProjectState restore(ProjectData data) {
        Path directory = UploadUtils.resolveWithin(storageRoot(), data.outputPath());
        Path source = UploadUtils.resolveWithin(storageRoot(), data.sourcePath());
        ProjectState state = new ProjectState(data.projectId(), data.owner(), data.projectName(),
                data.fileName(), directory, source, data.script() == null ? "" : data.script());
        state.revision = data.revision();
        state.voice = data.voice();
        state.speed = data.speed();
        state.pitch = data.pitch();
        state.rhythm = data.rhythm();
        state.audio = resolveOptional(data.audioPath());
        state.video = resolveOptional(data.videoPath());
        state.avatar = resolveOptional(data.avatarPath());
        state.status = data.status();
        state.errorMessage = data.errorMessage();
        state.createdAt = data.createdAt();
        state.updatedAt = data.updatedAt();
        for (RevisionData revision : projectRepository.findRevisions(data.projectId())) {
            state.revisions.add(new Revision(revision.revisionNumber(), revision.instruction(),
                    revision.script(), revision.createdAt()));
        }
        return state;
    }

    private Path resolveOptional(String storedPath) {
        return storedPath == null || storedPath.isBlank()
                ? null : UploadUtils.resolveWithin(storageRoot(), storedPath);
    }

    private String relativeKey(Path path) {
        if (path == null) {
            return null;
        }
        Path root = storageRoot();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("课件文件路径超出上传目录");
        }
        String key = root.relativize(normalized).toString().replace('\\', '/');
        UploadUtils.resolveWithin(root, key);
        return key;
    }

    private void begin(ProjectState state) {
        state.status = "PROCESSING";
        state.errorMessage = null;
        state.updatedAt = Instant.now();
        persist(state);
    }

    private void succeed(ProjectState state) {
        state.status = "SUCCEEDED";
        state.errorMessage = null;
        state.updatedAt = Instant.now();
        persist(state);
    }

    private void fail(ProjectState state, Exception exception) {
        state.status = "FAILED";
        String message = exception instanceof IOException
                ? "课件处理失败" : exception.getMessage();
        if (message == null || message.isBlank()) {
            message = "课件处理失败";
        }
        state.errorMessage = message.length() > 1000 ? message.substring(0, 1000) : message;
        state.updatedAt = Instant.now();
        persist(state);
    }

    private void saveScript(ProjectState state) throws IOException {
        Files.writeString(state.directory.resolve("script.txt"), state.script, StandardCharsets.UTF_8);
    }

    private String validatePpt(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 PPT 或 PPTX 文件");
        }
        String name = file.getOriginalFilename();
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!normalized.endsWith(".ppt") && !normalized.endsWith(".pptx")) {
            throw new IllegalArgumentException("仅支持 PPT 或 PPTX 文件");
        }
        return Path.of(name).getFileName().toString();
    }

    private void validateScript(String script) {
        if (script == null || script.isBlank() || script.length() > MAX_SCRIPT_LENGTH) {
            throw new IllegalArgumentException("讲稿长度必须在 1 到 50000 字之间");
        }
    }

    private void validateSpeechSettings(String voice, double speed, double pitch, double rhythm) {
        if (voice == null || voice.isBlank()) {
            throw new IllegalArgumentException("请选择音色");
        }
        if (!inSpeechRange(speed) || !inSpeechRange(pitch) || !inSpeechRange(rhythm)) {
            throw new IllegalArgumentException("语速、语调和节奏必须在 0.5 到 2.0 之间");
        }
    }

    private boolean inSpeechRange(double value) {
        return value >= 0.5 && value <= 2.0;
    }

    private List<String> splitScript(String script) {
        List<String> chunks = new ArrayList<>();
        String remaining = script.trim();
        while (remaining.length() > TTS_CHUNK_LENGTH) {
            int split = TTS_CHUNK_LENGTH;
            for (int index = TTS_CHUNK_LENGTH; index >= TTS_CHUNK_LENGTH - 500; index--) {
                if ("。！？；\n".indexOf(remaining.charAt(index - 1)) >= 0) {
                    split = index;
                    break;
                }
            }
            chunks.add(remaining.substring(0, split).trim());
            remaining = remaining.substring(split).trim();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private Path projectRoot() throws IOException {
        Path root = storageRoot();
        Files.createDirectories(root);
        return root;
    }

    private Path storageRoot() {
        return Path.of(coursewareDir).toAbsolutePath().normalize();
    }

    private String ffmpegPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "/").replace("'", "'\\''");
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String safeFileName(String value) {
        String safe = value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "课件" : safe;
    }

    private String normalizeOwner(String owner) {
        return owner == null || owner.isBlank() ? "anonymous" : owner;
    }

    @FunctionalInterface
    private interface SlidePainter {
        void paint(Graphics2D graphics);
    }

    private static final class ProjectState {
        private final String id;
        private final String owner;
        private final String title;
        private final String fileName;
        private final Path directory;
        private final Path source;
        private final List<Revision> revisions = new ArrayList<>();
        private String script;
        private int revision;
        private String voice = "longxiao";
        private double speed = 1.0;
        private double pitch = 1.0;
        private double rhythm = 1.0;
        private Path audio;
        private Path video;
        private Path avatar;
        private String status = "PENDING";
        private String errorMessage;
        private Instant createdAt = Instant.now();
        private Instant updatedAt = createdAt;

        private ProjectState(String id, String owner, String title, String fileName,
                             Path directory, Path source, String script) {
            this.id = id;
            this.owner = owner;
            this.title = title;
            this.fileName = fileName;
            this.directory = directory;
            this.source = source;
            this.script = script;
        }
    }

    private record Revision(int number, String instruction, String script, Instant createdAt) {
    }

    public record ProjectView(
            String id,
            String title,
            String fileName,
            String script,
            int revision,
            String voice,
            double speed,
            double pitch,
            double rhythm,
            boolean audioReady,
            boolean videoReady,
            boolean avatarReady,
            String status,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DownloadArtifact(Path path, String fileName, String contentType) {
    }
}

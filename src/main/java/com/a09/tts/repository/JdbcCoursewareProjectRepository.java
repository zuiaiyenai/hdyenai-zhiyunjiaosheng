package com.a09.tts.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!nodb")
public class JdbcCoursewareProjectRepository implements CoursewareProjectRepository {
    private static final String PROJECT_COLUMNS = """
            project_id, owner_username, project_name, status, source_path, output_path, file_name,
            script, revision, voice, speed, pitch, rhythm, audio_path, video_path, avatar_path,
            error_message, created_at, updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCoursewareProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(ProjectData project) {
        jdbcTemplate.update("""
                INSERT INTO courseware_project (
                    project_id, owner_username, project_name, status, source_path, output_path,
                    file_name, script, revision, voice, speed, pitch, rhythm, audio_path,
                    video_path, avatar_path, error_message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    project_name = VALUES(project_name), status = VALUES(status),
                    source_path = VALUES(source_path), output_path = VALUES(output_path),
                    file_name = VALUES(file_name), script = VALUES(script),
                    revision = VALUES(revision), voice = VALUES(voice),
                    speed = VALUES(speed), pitch = VALUES(pitch), rhythm = VALUES(rhythm),
                    audio_path = VALUES(audio_path), video_path = VALUES(video_path),
                    avatar_path = VALUES(avatar_path), error_message = VALUES(error_message),
                    updated_at = VALUES(updated_at)
                """,
                project.projectId(), project.owner(), project.projectName(), project.status(),
                project.sourcePath(), project.outputPath(), project.fileName(), project.script(),
                project.revision(), project.voice(), project.speed(), project.pitch(), project.rhythm(),
                project.audioPath(), project.videoPath(), project.avatarPath(), project.errorMessage(),
                Timestamp.from(project.createdAt()), Timestamp.from(project.updatedAt()));
    }

    @Override
    public Optional<ProjectData> findByIdAndOwner(String projectId, String owner) {
        List<ProjectData> projects = jdbcTemplate.query(
                "SELECT " + PROJECT_COLUMNS + " FROM courseware_project "
                        + "WHERE project_id = ? AND owner_username = ?",
                this::mapProject, projectId, owner);
        return projects.stream().findFirst();
    }

    @Override
    public void saveRevision(RevisionData revision) {
        jdbcTemplate.update("""
                INSERT INTO courseware_project_revision (
                    project_id, revision_number, instruction, script, created_at
                ) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    instruction = VALUES(instruction), script = VALUES(script),
                    created_at = VALUES(created_at)
                """,
                revision.projectId(), revision.revisionNumber(), revision.instruction(),
                revision.script(), Timestamp.from(revision.createdAt()));
    }

    @Override
    public List<RevisionData> findRevisions(String projectId) {
        return jdbcTemplate.query("""
                        SELECT project_id, revision_number, instruction, script, created_at
                        FROM courseware_project_revision
                        WHERE project_id = ?
                        ORDER BY revision_number
                        """,
                (resultSet, rowNumber) -> new RevisionData(
                        resultSet.getString("project_id"),
                        resultSet.getInt("revision_number"),
                        resultSet.getString("instruction"),
                        resultSet.getString("script"),
                        resultSet.getTimestamp("created_at").toInstant()),
                projectId);
    }

    private ProjectData mapProject(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProjectData(
                resultSet.getString("project_id"),
                resultSet.getString("owner_username"),
                resultSet.getString("project_name"),
                resultSet.getString("status"),
                resultSet.getString("source_path"),
                resultSet.getString("output_path"),
                resultSet.getString("file_name"),
                resultSet.getString("script"),
                resultSet.getInt("revision"),
                resultSet.getString("voice"),
                resultSet.getDouble("speed"),
                resultSet.getDouble("pitch"),
                resultSet.getDouble("rhythm"),
                resultSet.getString("audio_path"),
                resultSet.getString("video_path"),
                resultSet.getString("avatar_path"),
                resultSet.getString("error_message"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }
}

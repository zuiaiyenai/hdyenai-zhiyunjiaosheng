package com.a09.tts.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CoursewareProjectRepository {
    long save(ProjectData project);

    Optional<ProjectData> findByIdAndOwner(String projectId, String owner);

    List<ProjectData> findByOwner(String owner, int offset, int limit);

    void saveRevision(RevisionData revision);

    List<RevisionData> findRevisions(String projectId);

    List<RevisionData> findRevisionsByProjectIds(List<String> projectIds);

    record ProjectData(
            String projectId,
            String owner,
            String projectName,
            String status,
            String sourcePath,
            String outputPath,
            String fileName,
            String script,
            int revision,
            String voice,
            double speed,
            double pitch,
            double rhythm,
            String audioPath,
            String videoPath,
            String avatarPath,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt,
            long lockVersion
    ) {
        public ProjectData(
                String projectId, String owner, String projectName, String status,
                String sourcePath, String outputPath, String fileName, String script,
                int revision, String voice, double speed, double pitch, double rhythm,
                String audioPath, String videoPath, String avatarPath, String errorMessage,
                Instant createdAt, Instant updatedAt) {
            this(projectId, owner, projectName, status, sourcePath, outputPath, fileName,
                    script, revision, voice, speed, pitch, rhythm, audioPath, videoPath,
                    avatarPath, errorMessage, createdAt, updatedAt, -1);
        }

        public ProjectData withLockVersion(long version) {
            return new ProjectData(projectId, owner, projectName, status, sourcePath,
                    outputPath, fileName, script, revision, voice, speed, pitch, rhythm,
                    audioPath, videoPath, avatarPath, errorMessage, createdAt, updatedAt,
                    version);
        }
    }

    record RevisionData(
            String projectId,
            int revisionNumber,
            String instruction,
            String script,
            Instant createdAt
    ) {
    }
}

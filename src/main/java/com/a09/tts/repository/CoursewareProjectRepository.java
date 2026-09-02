package com.a09.tts.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CoursewareProjectRepository {
    void save(ProjectData project);

    Optional<ProjectData> findByIdAndOwner(String projectId, String owner);

    List<ProjectData> findByOwner(String owner, int offset, int limit);

    void saveRevision(RevisionData revision);

    List<RevisionData> findRevisions(String projectId);

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
            Instant updatedAt
    ) {
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

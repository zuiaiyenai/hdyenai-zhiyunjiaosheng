package com.a09.tts.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("nodb")
public class InMemoryCoursewareProjectRepository implements CoursewareProjectRepository {
    private final ConcurrentHashMap<String, ProjectData> projects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, RevisionData>> revisions =
            new ConcurrentHashMap<>();

    @Override
    public long save(ProjectData project) {
        if (project.lockVersion() < 0) {
            ProjectData existing = projects.putIfAbsent(
                    project.projectId(), project.withLockVersion(0));
            if (existing != null) {
                throw new OptimisticLockingFailureException(
                        "Courseware project already exists");
            }
            return 0;
        }
        long nextVersion = project.lockVersion() + 1;
        projects.compute(project.projectId(), (id, current) -> {
            if (current == null || !current.owner().equals(project.owner())
                    || current.lockVersion() != project.lockVersion()) {
                throw new OptimisticLockingFailureException(
                        "Courseware project was changed by another instance");
            }
            return project.withLockVersion(nextVersion);
        });
        return nextVersion;
    }

    @Override
    public Optional<ProjectData> findByIdAndOwner(String projectId, String owner) {
        ProjectData project = projects.get(projectId);
        return project != null && project.owner().equals(owner) ? Optional.of(project) : Optional.empty();
    }

    @Override
    public List<ProjectData> findByOwner(String owner, int offset, int limit) {
        return projects.values().stream()
                .filter(project -> project.owner().equals(owner))
                .sorted(Comparator.comparing(ProjectData::updatedAt).reversed()
                        .thenComparing(ProjectData::projectId, Comparator.reverseOrder()))
                .skip(offset)
                .limit(limit)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void saveRevision(RevisionData revision) {
        revisions.computeIfAbsent(revision.projectId(), ignored -> new ConcurrentHashMap<>())
                .put(revision.revisionNumber(), revision);
    }

    @Override
    public List<RevisionData> findRevisions(String projectId) {
        return revisions.getOrDefault(projectId, new ConcurrentHashMap<>()).values().stream()
                .sorted(Comparator.comparingInt(RevisionData::revisionNumber))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    @Override
    public List<RevisionData> findRevisionsByProjectIds(List<String> projectIds) {
        return projectIds.stream()
                .flatMap(projectId -> findRevisions(projectId).stream())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}

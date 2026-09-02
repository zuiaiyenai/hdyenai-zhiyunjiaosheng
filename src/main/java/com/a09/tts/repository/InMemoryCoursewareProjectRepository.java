package com.a09.tts.repository;

import org.springframework.context.annotation.Profile;
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
    public void save(ProjectData project) {
        projects.put(project.projectId(), project);
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
}

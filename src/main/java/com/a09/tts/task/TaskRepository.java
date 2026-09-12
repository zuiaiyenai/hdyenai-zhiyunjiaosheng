package com.a09.tts.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TaskRepository {
    default CreateResult create(TaskRecord task, int perUserConcurrency) {
        return create(task, perUserConcurrency, Integer.MAX_VALUE);
    }

    CreateResult create(TaskRecord task, int perUserConcurrency, int globalQueueLimit);

    Optional<TaskRecord> findById(String id);

    Optional<TaskRecord> findByIdAndOwner(String id, String owner);

    List<TaskRecord> findByOwner(String owner, int offset, int limit);

    Optional<TaskRecord> claimNext(String workerId, Instant now);

    boolean releaseClaim(String id, String workerId, String errorCode,
                         String errorMessage, Instant availableAt);

    boolean updateHeartbeat(String id, String workerId, Instant heartbeatAt);

    boolean completeSuccess(String id, String workerId, String resultData, Instant finishedAt);

    boolean reschedule(String id, String workerId, String errorCode,
                       String errorMessage, Instant availableAt);

    boolean completeFailure(String id, String workerId, String errorCode,
                            String errorMessage, Instant finishedAt);

    boolean completeTimeout(String id, String workerId, String errorMessage, Instant finishedAt);

    RecoveryResult recoverStale(Instant staleBefore, Instant availableAt);

    boolean markRunning(String id, Instant startedAt);

    boolean markSucceeded(String id, String resultData, Instant finishedAt);

    boolean markFailed(String id, String errorMessage, Instant finishedAt);

    boolean markTimedOut(String id, String errorMessage, Instant finishedAt);

    boolean markCancelled(String id, String owner, String errorMessage, Instant finishedAt);

    enum CreateDisposition {
        CREATED,
        DUPLICATE,
        USER_CAPACITY_EXCEEDED,
        GLOBAL_CAPACITY_EXCEEDED
    }

    record CreateResult(CreateDisposition disposition, TaskRecord task) {
        public static CreateResult created(TaskRecord task) {
            return new CreateResult(CreateDisposition.CREATED, task);
        }

        public static CreateResult duplicate(TaskRecord task) {
            return new CreateResult(CreateDisposition.DUPLICATE, task);
        }

        public static CreateResult userCapacityExceeded() {
            return new CreateResult(CreateDisposition.USER_CAPACITY_EXCEEDED, null);
        }

        public static CreateResult globalCapacityExceeded() {
            return new CreateResult(CreateDisposition.GLOBAL_CAPACITY_EXCEEDED, null);
        }
    }

    record RecoveryResult(int requeued, List<TaskRecord> failed) {
    }
}

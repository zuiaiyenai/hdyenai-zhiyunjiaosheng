package com.a09.tts.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!nodb")
public class JdbcTaskRepository implements TaskRepository {
    private static final String COLUMNS = """
            task_id, owner_username, task_type, status, progress, payload_json,
            attempts, max_attempts, available_at, result_data, error_code,
            error_message, deduplication_key, created_at, started_at, heartbeat_at,
            finished_at, worker_id, version
            """;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcTaskRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new DataSourceTransactionManager(
                Objects.requireNonNull(jdbcTemplate.getDataSource(), "Task datasource is required")));
    }

    @Autowired
    public JdbcTaskRepository(JdbcTemplate jdbcTemplate,
                              PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public CreateResult create(TaskRecord task, int perUserConcurrency) {
        CreateResult result = transactionTemplate.execute(status -> {
            try {
                insert(task);
            } catch (DuplicateKeyException exception) {
                if (task.deduplicationKey() == null) {
                    throw exception;
                }
                TaskRecord existing = findActiveByDeduplication(
                        task.owner(), task.type(), task.deduplicationKey()).orElse(null);
                if (existing == null) {
                    throw exception;
                }
                return CreateResult.duplicate(existing);
            }
            if (!reserveUserSlot(task.owner(), task.id(), perUserConcurrency)) {
                status.setRollbackOnly();
                return CreateResult.capacityExceeded();
            }
            return CreateResult.created(task);
        });
        return Objects.requireNonNull(result, "Task creation transaction returned no result");
    }

    @Override
    public Optional<TaskRecord> findById(String id) {
        return query("WHERE task_id = ?", id);
    }

    @Override
    public Optional<TaskRecord> findByIdAndOwner(String id, String owner) {
        return query("WHERE task_id = ? AND owner_username = ?", id, owner);
    }

    @Override
    public List<TaskRecord> findByOwner(String owner, int offset, int limit) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM async_task "
                        + "WHERE owner_username = ? "
                        + "ORDER BY created_at DESC, task_id DESC LIMIT ? OFFSET ?",
                this::map, owner, limit, offset);
    }

    @Override
    public Optional<TaskRecord> claimNext(String workerId, Instant now) {
        int updated = jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'RUNNING', progress = 10, attempts = attempts + 1,
                    started_at = ?, heartbeat_at = ?, worker_id = ?,
                    error_code = NULL, error_message = NULL, version = version + 1
                WHERE status = 'PENDING' AND available_at <= ?
                ORDER BY available_at, created_at, task_id
                LIMIT 1
                """, timestamp(now), timestamp(now), workerId, timestamp(now));
        return updated == 1
                ? query("WHERE worker_id = ? AND status = 'RUNNING'", workerId)
                : Optional.empty();
    }

    @Override
    public boolean releaseClaim(String id, String workerId, String errorCode,
                                String errorMessage, Instant availableAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'PENDING', progress = 0, attempts = attempts - 1,
                    available_at = ?, started_at = NULL, heartbeat_at = NULL,
                    worker_id = NULL, error_code = ?, error_message = ?,
                    version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                  AND attempts > 0
                """, timestamp(availableAt), errorCode, errorMessage, id, workerId) == 1;
    }

    @Override
    public boolean updateHeartbeat(String id, String workerId, Instant heartbeatAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET heartbeat_at = ?, version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, timestamp(heartbeatAt), id, workerId) == 1;
    }

    @Override
    public boolean completeSuccess(
            String id, String workerId, String resultData, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'SUCCESS', progress = 100, result_data = ?,
                    error_code = NULL, error_message = NULL, finished_at = ?,
                    worker_id = NULL, version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, resultData, timestamp(finishedAt), id, workerId);
    }

    @Override
    public boolean reschedule(
            String id, String workerId, String errorCode,
            String errorMessage, Instant availableAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'PENDING', progress = 0, result_data = NULL,
                    error_code = ?, error_message = ?, available_at = ?,
                    started_at = NULL, heartbeat_at = NULL, finished_at = NULL,
                    worker_id = NULL, version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, errorCode, errorMessage, timestamp(availableAt), id, workerId) == 1;
    }

    @Override
    public boolean completeFailure(
            String id, String workerId, String errorCode,
            String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'FAILED', result_data = NULL, error_code = ?,
                    error_message = ?, finished_at = ?, worker_id = NULL,
                    version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, errorCode, errorMessage, timestamp(finishedAt), id, workerId);
    }

    @Override
    public boolean completeTimeout(
            String id, String workerId, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'TIMEOUT', result_data = NULL, error_code = 'TASK_TIMEOUT',
                    error_message = ?, finished_at = ?, worker_id = NULL,
                    version = version + 1
                WHERE task_id = ? AND status = 'RUNNING' AND worker_id = ?
                """, errorMessage, timestamp(finishedAt), id, workerId);
    }

    @Override
    public RecoveryResult recoverStale(Instant staleBefore, Instant availableAt) {
        RecoveryResult recovered = transactionTemplate.execute(status -> {
            String recoveryToken = "recovery:" + UUID.randomUUID();
            int failed = jdbcTemplate.update("""
                    UPDATE async_task
                    SET status = 'FAILED', result_data = NULL,
                        error_code = 'STALE_ATTEMPTS_EXHAUSTED',
                        error_message = '任务心跳超时且重试次数已耗尽',
                        finished_at = ?, worker_id = ?, version = version + 1
                    WHERE status = 'RUNNING' AND heartbeat_at < ?
                      AND attempts >= max_attempts
                    """, timestamp(availableAt), recoveryToken, timestamp(staleBefore));
            if (failed > 0) {
                List<TaskRecord> failedTasks = jdbcTemplate.query(
                        "SELECT " + COLUMNS + " FROM async_task WHERE worker_id = ?",
                        this::map, recoveryToken);
                jdbcTemplate.update("""
                        DELETE slot FROM async_task_user_slot slot
                        INNER JOIN async_task task ON task.task_id = slot.task_id
                        WHERE task.worker_id = ? AND task.status = 'FAILED'
                        """, recoveryToken);
                jdbcTemplate.update("""
                        UPDATE async_task SET worker_id = NULL
                        WHERE worker_id = ? AND status = 'FAILED'
                        """, recoveryToken);
                int requeued = requeueStale(staleBefore, availableAt);
                return new RecoveryResult(requeued, failedTasks);
            }
            return new RecoveryResult(requeueStale(staleBefore, availableAt), List.of());
        });
        return recovered == null ? new RecoveryResult(0, List.of()) : recovered;
    }

    private int requeueStale(Instant staleBefore, Instant availableAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'PENDING', progress = 0, result_data = NULL,
                    error_code = 'STALE_RECOVERED',
                    error_message = '任务因 worker 心跳超时重新排队',
                    available_at = ?, started_at = NULL, heartbeat_at = NULL,
                    finished_at = NULL, worker_id = NULL, version = version + 1
                WHERE status = 'RUNNING' AND heartbeat_at < ?
                  AND attempts < max_attempts
                """, timestamp(availableAt), timestamp(staleBefore));
    }

    @Override
    public boolean markRunning(String id, Instant startedAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'RUNNING', progress = 10, attempts = attempts + 1,
                    started_at = ?, heartbeat_at = ?, version = version + 1
                WHERE task_id = ? AND status = 'PENDING'
                """, timestamp(startedAt), timestamp(startedAt), id) == 1;
    }

    @Override
    public boolean markSucceeded(String id, String resultData, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'SUCCESS', progress = 100, result_data = ?,
                    error_code = NULL, error_message = NULL, finished_at = ?,
                    worker_id = NULL, version = version + 1
                WHERE task_id = ? AND status = 'RUNNING'
                """, resultData, timestamp(finishedAt), id);
    }

    @Override
    public boolean markFailed(String id, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'FAILED', result_data = NULL, error_code = 'TASK_FAILED',
                    error_message = ?, finished_at = ?, worker_id = NULL,
                    version = version + 1
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, timestamp(finishedAt), id);
    }

    @Override
    public boolean markTimedOut(String id, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'TIMEOUT', result_data = NULL, error_code = 'TASK_TIMEOUT',
                    error_message = ?, finished_at = ?, worker_id = NULL,
                    version = version + 1
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, timestamp(finishedAt), id);
    }

    @Override
    public boolean markCancelled(String id, String owner, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'CANCELLED', error_code = 'TASK_CANCELLED',
                    error_message = ?, finished_at = ?, worker_id = NULL,
                    version = version + 1
                WHERE task_id = ? AND owner_username = ?
                  AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, timestamp(finishedAt), id, owner);
    }

    private void insert(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO async_task (
                    task_id, owner_username, task_type, status, progress, payload_json,
                    attempts, max_attempts, available_at, result_data, error_code,
                    error_message, deduplication_key, created_at, started_at, heartbeat_at,
                    finished_at, worker_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, task.id(), task.owner(), task.type(), task.status().name(), task.progress(),
                task.payload(), task.attempts(), task.maxAttempts(), timestamp(task.availableAt()),
                task.resultData(), task.errorCode(), task.errorMessage(), task.deduplicationKey(),
                timestamp(task.createdAt()), timestamp(task.startedAt()),
                timestamp(task.heartbeatAt()), timestamp(task.finishedAt()),
                task.workerId(), task.version());
    }

    private Optional<TaskRecord> findActiveByDeduplication(
            String owner, String type, String deduplicationKey) {
        return query("""
                WHERE owner_username = ? AND task_type = ?
                  AND active_deduplication_key = ?
                """, owner, type, deduplicationKey);
    }

    private boolean reserveUserSlot(String owner, String taskId, int perUserConcurrency) {
        for (int slot = 1; slot <= perUserConcurrency; slot++) {
            int inserted = jdbcTemplate.update("""
                    INSERT IGNORE INTO async_task_user_slot (owner_username, slot_number, task_id)
                    VALUES (?, ?, ?)
                    """, owner, slot, taskId);
            if (inserted == 1) {
                return true;
            }
        }
        return false;
    }

    private boolean terminalTransition(String taskId, String sql, Object... arguments) {
        Boolean transitioned = transactionTemplate.execute(status -> {
            int updated = jdbcTemplate.update(sql, arguments);
            if (updated == 1) {
                jdbcTemplate.update("DELETE FROM async_task_user_slot WHERE task_id = ?", taskId);
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(transitioned);
    }

    private Optional<TaskRecord> query(String where, Object... arguments) {
        List<TaskRecord> tasks = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM async_task " + where,
                this::map, arguments);
        return tasks.stream().findFirst();
    }

    private TaskRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new TaskRecord(
                resultSet.getString("task_id"), resultSet.getString("owner_username"),
                resultSet.getString("task_type"),
                TaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("progress"), resultSet.getString("payload_json"),
                resultSet.getString("result_data"), resultSet.getString("error_code"),
                resultSet.getString("error_message"), resultSet.getString("deduplication_key"),
                resultSet.getInt("attempts"), resultSet.getInt("max_attempts"),
                resultSet.getTimestamp("available_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("heartbeat_at")),
                instant(resultSet.getTimestamp("finished_at")),
                resultSet.getString("worker_id"), resultSet.getLong("version"));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

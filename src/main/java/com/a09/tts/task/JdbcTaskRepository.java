package com.a09.tts.task;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.annotation.Profile;
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

@Repository
@Profile("!nodb")
public class JdbcTaskRepository implements TaskRepository {
    private static final String COLUMNS = """
            task_id, owner_username, task_type, status, progress, result_data,
            error_message, deduplication_key, created_at, started_at, finished_at
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
    public boolean markRunning(String id, Instant startedAt) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'RUNNING', progress = 10, started_at = ?, error_message = NULL
                WHERE task_id = ? AND status = 'PENDING'
                """, Timestamp.from(startedAt), id) == 1;
    }

    @Override
    public boolean markSucceeded(String id, String resultData, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'SUCCESS', progress = 100, result_data = ?,
                    error_message = NULL, finished_at = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """, resultData, Timestamp.from(finishedAt), id);
    }

    @Override
    public boolean markFailed(String id, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'FAILED', result_data = NULL, error_message = ?, finished_at = ?
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, Timestamp.from(finishedAt), id);
    }

    @Override
    public boolean markTimedOut(String id, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'TIMEOUT', result_data = NULL, error_message = ?, finished_at = ?
                WHERE task_id = ? AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, Timestamp.from(finishedAt), id);
    }

    @Override
    public boolean markCancelled(String id, String owner, String errorMessage, Instant finishedAt) {
        return terminalTransition(id, """
                UPDATE async_task
                SET status = 'CANCELLED', error_message = ?, finished_at = ?
                WHERE task_id = ? AND owner_username = ? AND status IN ('PENDING', 'RUNNING')
                """, errorMessage, Timestamp.from(finishedAt), id, owner);
    }

    private void insert(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO async_task (
                    task_id, owner_username, task_type, status, progress, result_data,
                    error_message, deduplication_key, created_at, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task.id(), task.owner(), task.type(), task.status().name(), task.progress(),
                task.resultData(), task.errorMessage(), task.deduplicationKey(),
                timestamp(task.createdAt()), timestamp(task.startedAt()), timestamp(task.finishedAt()));
    }

    private Optional<TaskRecord> findActiveByDeduplication(
            String owner, String type, String deduplicationKey) {
        return query("""
                WHERE owner_username = ? AND task_type = ? AND deduplication_key = ?
                  AND status IN ('PENDING', 'RUNNING')
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
                resultSet.getString("task_id"),
                resultSet.getString("owner_username"),
                resultSet.getString("task_type"),
                TaskStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("progress"),
                resultSet.getString("result_data"),
                resultSet.getString("error_message"),
                resultSet.getString("deduplication_key"),
                resultSet.getTimestamp("created_at").toInstant(),
                instant(resultSet.getTimestamp("started_at")),
                instant(resultSet.getTimestamp("finished_at")));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

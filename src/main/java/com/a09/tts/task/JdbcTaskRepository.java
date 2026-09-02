package com.a09.tts.task;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("!nodb")
public class JdbcTaskRepository implements TaskRepository {
    private static final String COLUMNS = """
            task_id, owner_username, task_type, status, progress, result_data,
            error_message, deduplication_key, created_at, started_at, finished_at
            """;
    private final JdbcTemplate jdbcTemplate;

    public JdbcTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void save(TaskRecord task) {
        jdbcTemplate.update("""
                INSERT INTO async_task (
                    task_id, owner_username, task_type, status, progress, result_data,
                    error_message, deduplication_key, created_at, started_at, finished_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    status = VALUES(status), progress = VALUES(progress),
                    result_data = VALUES(result_data), error_message = VALUES(error_message),
                    started_at = VALUES(started_at), finished_at = VALUES(finished_at)
                """,
                task.id(), task.owner(), task.type(), task.status().name(), task.progress(),
                task.resultData(), task.errorMessage(), task.deduplicationKey(),
                timestamp(task.createdAt()), timestamp(task.startedAt()), timestamp(task.finishedAt()));
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
    public int markInterruptedTasksFailed(Instant finishedAt, String reason) {
        return jdbcTemplate.update("""
                UPDATE async_task
                SET status = 'FAILED', error_message = ?, finished_at = ?
                WHERE status IN ('PENDING', 'RUNNING')
                """, reason, Timestamp.from(finishedAt));
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

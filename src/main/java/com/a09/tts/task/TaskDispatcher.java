package com.a09.tts.task;

public interface TaskDispatcher {
    String execute(TaskRecord task) throws Exception;

    void cleanup(TaskRecord task);

    void cleanupUncommittedResult(TaskRecord task, String resultData);
}

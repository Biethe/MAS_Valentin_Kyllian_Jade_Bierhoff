package com.assistant.mail.model;

public class Task {
    private String taskId;
    private String description;
    private String derivedInstruction;
    private Long targetEmailId;

    public Task(String taskId, String description, String derivedInstruction, Long targetEmailId) {
        this.taskId = taskId;
        this.description = description;
        this.derivedInstruction = derivedInstruction;
        this.targetEmailId = targetEmailId;
    }

    public String getTaskId() { return taskId; }
    public String getDescription() { return description; }
    public String getDerivedInstruction() { return derivedInstruction; }
    public Long getTargetEmailId() { return targetEmailId; }
}

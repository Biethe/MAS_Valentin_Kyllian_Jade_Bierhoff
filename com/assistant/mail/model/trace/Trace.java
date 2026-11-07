package com.assistant.mail.model.trace;

import com.assistant.mail.model.plan.InstructionType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Trace {
    private String id = "";
    private Instant at;
    private InstructionType task;
    private String requestText = "";
    private List<TraceStep> steps = new ArrayList<>();

    public Trace() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id == null ? "" : id; }

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public InstructionType getTask() { return task; }
    public void setTask(InstructionType task) { this.task = task; }

    public String getRequestText() { return requestText; }
    public void setRequestText(String requestText) {
        this.requestText = requestText == null ? "" : requestText;
    }

    public List<TraceStep> getSteps() { return steps; }
    public void setSteps(List<TraceStep> steps) {
        this.steps = steps == null ? new ArrayList<>() : steps;
    }
}

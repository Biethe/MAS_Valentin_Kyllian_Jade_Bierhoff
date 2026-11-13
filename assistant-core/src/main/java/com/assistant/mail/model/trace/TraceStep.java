package com.assistant.mail.model.trace;

import java.time.Instant;

public class TraceStep {
    private Instant at;
    private TraceActor actor;
    private String action = "";
    private String detail = "";
    private String error = "";

    public TraceStep() {}

    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public TraceActor getActor() { return actor; }
    public void setActor(TraceActor actor) { this.actor = actor; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action == null ? "" : action; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail == null ? "" : detail; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error == null ? "" : error; }
}

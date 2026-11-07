package com.assistant.mail.model.fact;

import java.time.Duration;
import java.time.Instant;

public class Information {
    private String body;
    private InformationKind kind = InformationKind.INFORMATIVE;
    private Instant dueTime;

    public Information() {}

    public Information(String body, InformationKind kind, Instant dueTime) {
        this.body = body;
        this.kind = kind;
        this.dueTime = kind == InformationKind.REQUIRED_ACTION ? dueTime : null;
    }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public InformationKind getKind() { return kind; }
    public void setKind(InformationKind kind) { this.kind = kind; }

    public Instant getDueTime() { return dueTime; }
    public void setDueTime(Instant dueTime) { this.dueTime = dueTime; }

    public boolean isUrgent() {
        return kind == InformationKind.REQUIRED_ACTION
            && dueTime != null
            && dueTime.isBefore(Instant.now().plus(Duration.ofHours(24)));
    }
}

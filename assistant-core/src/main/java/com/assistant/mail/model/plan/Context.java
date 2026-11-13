package com.assistant.mail.model.plan;

import java.util.ArrayList;
import java.util.List;

public class Context {
    private UserIntention intention;
    private List<Long> emailIds = new ArrayList<>();
    private List<String> threadIds = new ArrayList<>();
    private List<String> attachedFiles = new ArrayList<>();
    private List<String> fromUserFiles = new ArrayList<>();

    public Context() {}

    public UserIntention getIntention() { return intention; }
    public void setIntention(UserIntention intention) { this.intention = intention; }

    public List<Long> getEmailIds() { return emailIds; }
    public void setEmailIds(List<Long> emailIds) { this.emailIds = emailIds; }

    public List<String> getThreadIds() { return threadIds; }
    public void setThreadIds(List<String> threadIds) { this.threadIds = threadIds; }

    public List<String> getAttachedFiles() { return attachedFiles; }
    public void setAttachedFiles(List<String> attachedFiles) { this.attachedFiles = attachedFiles; }

    public List<String> getFromUserFiles() { return fromUserFiles; }
    public void setFromUserFiles(List<String> fromUserFiles) { this.fromUserFiles = fromUserFiles; }
}

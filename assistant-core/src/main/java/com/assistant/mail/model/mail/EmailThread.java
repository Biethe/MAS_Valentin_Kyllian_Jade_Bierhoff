package com.assistant.mail.model.mail;

import java.util.ArrayList;
import java.util.List;

public class EmailThread {
    private String threadId;
    private List<Email> emails = new ArrayList<>();

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public List<Email> getEmails() { return emails; }
    public void setEmails(List<Email> emails) { this.emails = emails; }
}

package com.assistant.mail.model.mail;

import com.assistant.mail.model.fact.Information;

import java.util.ArrayList;
import java.util.List;

public class Email {
    private Long id;
    private String threadId;
    private User sender;
    private List<String> recipients = new ArrayList<>();
    private String subject;
    private String body;
    private List<Information> information = new ArrayList<>();
    private List<AttachmentFile> attachments = new ArrayList<>();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }

    public User getSender() { return sender; }
    public void setSender(User sender) { this.sender = sender; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<Information> getInformation() { return information; }
    public void setInformation(List<Information> information) { this.information = information; }

    public List<AttachmentFile> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentFile> attachments) { this.attachments = attachments; }

}

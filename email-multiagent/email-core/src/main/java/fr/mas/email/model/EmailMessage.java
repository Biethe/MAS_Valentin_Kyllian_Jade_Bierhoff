package fr.mas.email.model;

import java.util.Objects;

/** A single email message. */
public final class EmailMessage {

    private final String sender;
    private final String recipient;
    private final String subject;
    private final String body;
    private final String threadId;

    public EmailMessage(String sender, String recipient, String subject, String body, String threadId) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.recipient = Objects.requireNonNull(recipient, "recipient");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.body = Objects.requireNonNull(body, "body");
        this.threadId = threadId == null ? "" : threadId;
    }

    public String getSender() {
        return sender;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getThreadId() {
        return threadId;
    }

    @Override
    public String toString() {
        return "From: " + sender + "\nTo: " + recipient + "\nSubject: " + subject + "\n\n" + body;
    }
}

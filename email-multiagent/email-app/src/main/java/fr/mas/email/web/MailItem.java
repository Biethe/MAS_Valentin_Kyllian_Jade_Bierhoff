package fr.mas.email.web;

/** A mail item as shown by the web API. */
public record MailItem(String sender,
                       String recipient,
                       String subject,
                       String body,
                       String threadId,
                       String category,
                       boolean quarantined,
                       String reason) {
}

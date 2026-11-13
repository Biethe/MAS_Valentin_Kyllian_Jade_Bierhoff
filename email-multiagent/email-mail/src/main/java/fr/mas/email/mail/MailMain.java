package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;
import fr.mas.email.model.EmailMessage;

import java.util.List;

/** CLI entry point for the IMAP integration. */
public final class MailMain {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        MailClient client = new ImapMailClient(config);
        System.out.println("Logged in: " + client.isLoggedIn());
        System.out.println("User: " + client.userEmail());
        List<EmailMessage> messages = client.fetchInbox(config.getInt("mail.top", 10));
        for (EmailMessage message : messages) {
            System.out.println("---");
            System.out.println("From: " + message.getSender());
            System.out.println("Subject: " + message.getSubject());
            System.out.println("Thread: " + message.getThreadId());
            String body = message.getBody();
            System.out.println("Body: " + (body.length() > 300 ? body.substring(0, 300) + "..." : body));
        }
    }
}

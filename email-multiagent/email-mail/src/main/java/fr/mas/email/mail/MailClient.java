package fr.mas.email.mail;

import fr.mas.email.model.EmailMessage;

import java.util.List;

/** Abstraction over a mail provider used to read the inbox. */
public interface MailClient {

    boolean isLoggedIn();

    String userEmail();

    List<EmailMessage> fetchInbox(int limit);
}

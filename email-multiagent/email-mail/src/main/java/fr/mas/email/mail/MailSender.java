package fr.mas.email.mail;

import fr.mas.email.model.EmailMessage;

/** Sends an approved reply. */
public interface MailSender {

    void send(EmailMessage original, String reply);
}

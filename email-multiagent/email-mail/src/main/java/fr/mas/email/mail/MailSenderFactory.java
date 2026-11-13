package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;

/** Builds the mail sender. */
public final class MailSenderFactory {

    private MailSenderFactory() {
    }

    public static MailSender create(AppConfig config) {
        return new SmtpMailSender(config);
    }
}

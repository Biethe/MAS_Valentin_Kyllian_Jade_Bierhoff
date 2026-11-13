package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;

/** Builds the mail client selected by the {@code mail.provider} setting. */
public final class MailClientFactory {

    private MailClientFactory() {
    }

    public static MailClient create(AppConfig config) {
        return new ImapMailClient(config);
    }
}

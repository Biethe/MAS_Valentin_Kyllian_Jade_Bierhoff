package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;

/**
 * Fabrique du client de messagerie (pattern <em>Factory</em>).
 */
public final class MailClientFactory {

    private MailClientFactory() {
    }

    public static MailClient create(AppConfig config) {
        if (!config.outlookEnabled()) {
            return new DisabledMailClient();
        }
        if (config.outlookClientId().isBlank()) {
            throw new MailException("outlook.clientId est requis lorsque outlook.enabled=true");
        }
        return new OutlookMailClient(config, new GraphAuth(config));
    }
}

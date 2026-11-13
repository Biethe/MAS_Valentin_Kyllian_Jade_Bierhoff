package fr.mas.email.mail;

/**
 * Erreur de la couche messagerie (Outlook / Graph).
 */
public class MailException extends RuntimeException {

    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}

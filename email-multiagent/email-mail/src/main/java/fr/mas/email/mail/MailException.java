package fr.mas.email.mail;

/** Raised when a mail provider cannot complete an operation. */
public class MailException extends RuntimeException {

    public MailException(String message) {
        super(message);
    }

    public MailException(String message, Throwable cause) {
        super(message, cause);
    }
}

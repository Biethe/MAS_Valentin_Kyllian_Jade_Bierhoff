package fr.mas.email.mail;

import fr.mas.email.model.EmailMessage;

import java.util.List;

/**
 * Abstraction d'une boite mail distante (pattern <em>Adapter</em>).
 *
 * <p>Permet de brancher Outlook/Microsoft Graph, mais aussi un autre
 * fournisseur (IMAP, Gmail...) sans modifier le reste du systeme.</p>
 */
public interface MailClient extends AutoCloseable {

    boolean isEnabled();

    /** Recupere les derniers messages de la boite de reception. */
    List<EmailMessage> fetchInbox(int limit);

    /** Envoie une reponse dans le fil du message d'origine. */
    void sendReply(EmailMessage original, String content);

    @Override
    default void close() {
    }
}

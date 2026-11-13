package fr.mas.email.model;

import java.time.Instant;

/**
 * Email entrant dans le systeme.
 *
 * <p>{@code externalId} est l'identifiant du message dans le serveur de mail
 * (Microsoft Graph pour Outlook) ; il est necessaire pour repondre dans le fil.</p>
 */
public record EmailMessage(
        String fromAddress,
        String fromName,
        String subject,
        String body,
        Instant receivedAt,
        String threadId,
        SenderRole senderRole,
        String externalId) {

    public EmailMessage(String fromAddress,
                        String fromName,
                        String subject,
                        String body,
                        Instant receivedAt,
                        String threadId,
                        SenderRole senderRole) {
        this(fromAddress, fromName, subject, body, receivedAt, threadId, senderRole, null);
    }

    public EmailMessage {
        if (threadId == null || threadId.isBlank()) {
            threadId = "thread-" + Math.abs((fromAddress + subject).hashCode());
        }
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (senderRole == null) {
            senderRole = SenderRole.UNKNOWN;
        }
    }

    public String senderLabel() {
        if (fromName == null || fromName.isBlank()) {
            return fromAddress;
        }
        return fromName + " <" + fromAddress + ">";
    }

    /** Rendu textuel envoye au LLM. */
    public String toPromptText() {
        return "De : " + senderLabel() + "\n"
                + "Role connu : " + senderRole + "\n"
                + "Sujet : " + subject + "\n"
                + "Fil : " + threadId + "\n"
                + "Date : " + receivedAt + "\n\n"
                + body;
    }
}

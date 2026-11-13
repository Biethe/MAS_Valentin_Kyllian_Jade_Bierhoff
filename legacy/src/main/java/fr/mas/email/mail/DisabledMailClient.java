package fr.mas.email.mail;

import fr.mas.email.model.EmailMessage;

import java.util.List;

/**
 * Implementation inactive : le systeme fonctionne alors en mode console/mock.
 */
public final class DisabledMailClient implements MailClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<EmailMessage> fetchInbox(int limit) {
        throw new IllegalStateException("Integration Outlook desactivee (outlook.enabled=false)");
    }

    @Override
    public void sendReply(EmailMessage original, String content) {
        throw new IllegalStateException("Integration Outlook desactivee (outlook.enabled=false)");
    }
}

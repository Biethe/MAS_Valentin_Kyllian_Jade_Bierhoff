package fr.mas.email.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.model.mail.Email;
import fr.mas.email.config.AppConfig;
import fr.mas.email.mail.MailClient;
import fr.mas.email.model.EmailMessage;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PlannerBridgeTest {

    private static final EmailMessage ADA = new EmailMessage("Ada Lovelace <ada@u.test>", "moi@u.test", "RDV",
            "Réunion vendredi à 10h.", "");

    private static final MailClient INBOX = new MailClient() {
        public boolean isLoggedIn() { return true; }
        public String userEmail() { return "moi@u.test"; }
        public List<EmailMessage> fetchInbox(int limit) { return List.of(ADA); }
    };

    private static PlannerBridge bridge() {
        Properties p = new Properties();
        p.setProperty("llm.client", "mock");
        return new PlannerBridge(AppConfig.of(p), INBOX, 10);
    }

    @Test void convertsImapMessagesIntoAssistantMails() {
        List<Email> mails = PlannerBridge.convert(List.of(ADA));
        assertEquals(1, mails.size());
        assertEquals(1L, mails.get(0).getId());
        assertEquals("ada@u.test", mails.get(0).getSender().getEmail());
        assertEquals("Ada Lovelace", mails.get(0).getSender().getFullName());
        assertEquals("Réunion vendredi à 10h.", mails.get(0).getBody());
    }

    @Test void answersARequestFromTheImapInbox() {
        Map<String, Object> out = bridge().ask("Réponds à Ada");
        assertTrue(String.valueOf(out.get("drafts")).contains("ada@u.test"), out.toString());
    }

    @Test void summarisesFromTheImapInbox() {
        Map<String, Object> out = bridge().ask("Résume le mail d'Ada");
        assertTrue(String.valueOf(out.get("summaries")).contains("Réunion"), out.toString());
    }
}

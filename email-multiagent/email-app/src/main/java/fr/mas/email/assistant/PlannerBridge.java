package fr.mas.email.assistant;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.OutcomeJson;
import com.assistant.mail.llm.OllamaChat;
import com.assistant.mail.llm.ScriptedModels;
import com.assistant.mail.mailbox.InMemoryMailbox;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.User;
import fr.mas.email.config.AppConfig;
import fr.mas.email.mail.MailClient;
import fr.mas.email.model.EmailMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plugs the Planner / Writer / Summary workflows of {@code assistant-core} into this application:
 * the mails come from the IMAP client already configured here, the model from {@code llm.*} in
 * config.properties. Drafts stay in memory and are never sent from here.
 */
public final class PlannerBridge {

    private static final long MAX_AGE_MS = 60_000;
    private static final Pattern ADDRESS = Pattern.compile("<([^>]+)>");

    private final MailClient mailClient;
    private final int mailTop;
    private final InMemoryMailbox mailbox = new InMemoryMailbox(List.of());
    private final Assistant assistant;
    private long loadedAt;

    public PlannerBridge(AppConfig config, MailClient mailClient, int mailTop) {
        this.mailClient = mailClient;
        this.mailTop = mailTop;
        if ("mock".equalsIgnoreCase(config.get("llm.client", "ollama"))) {
            ScriptedModels models = new ScriptedModels();
            this.assistant = new Assistant(mailbox, models, models, models, "scripted models + IMAP inbox", () -> { });
        } else {
            OllamaChat chat = new OllamaChat(config.get("llm.endpoint", "http://localhost:11434"),
                    config.get("llm.model", "llama3.1:8b"));
            this.assistant = new Assistant(mailbox, chat, chat, chat, chat.model() + " + IMAP inbox", () -> { });
        }
    }

    public Assistant assistant() {
        return assistant;
    }

    /** Answers one request, reloading the inbox first when it is older than a minute. */
    public synchronized Map<String, Object> ask(String text) {
        refresh(false);
        return OutcomeJson.of(assistant.ask(text), true);
    }

    public synchronized void refresh(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && loadedAt != 0 && now - loadedAt < MAX_AGE_MS) return;
        mailbox.replaceMails(convert(mailClient.fetchInbox(mailTop)));
        loadedAt = now;
    }

    static List<Email> convert(List<EmailMessage> messages) {
        List<Email> mails = new ArrayList<>();
        long id = 1;
        for (EmailMessage m : messages) {
            Email email = new Email();
            email.setId(id++);
            email.setThreadId(m.getThreadId().isBlank() ? "t" + email.getId() : m.getThreadId());
            email.setSender(user(m.getSender()));
            email.setRecipients(new ArrayList<>(List.of(m.getRecipient())));
            email.setSubject(m.getSubject());
            email.setBody(m.getBody());
            mails.add(email);
        }
        return mails;
    }

    private static User user(String sender) {
        Matcher matcher = ADDRESS.matcher(sender);
        String address = matcher.find() ? matcher.group(1).strip() : sender.strip();
        String name = matcher.find(0) ? sender.substring(0, sender.indexOf('<')).replace("\"", "").strip() : "";
        return new User(address, name.isEmpty() ? null : name, null, null, address);
    }
}

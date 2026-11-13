package fr.mas.email.memory;

import fr.mas.email.model.EmailMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoire conversationnelle partagee.
 *
 * <p>Conserve l'historique de chaque fil de discussion afin de ne pas repondre
 * uniquement au dernier message. Seule une fenetre glissante est envoyee au LLM
 * pour limiter le volume de contexte.</p>
 */
public final class ConversationMemory {

    public enum Kind {
        INCOMING,
        PROPOSED,
        SENT
    }

    public record Entry(Kind kind, String content, Instant at) {
    }

    private final Map<String, LinkedList<Entry>> threads = new ConcurrentHashMap<>();

    public void rememberEmail(EmailMessage email) {
        String content = "Sujet : " + email.subject() + "\n" + email.body();
        add(email.threadId(), new Entry(Kind.INCOMING, content, email.receivedAt()));
    }

    public void rememberProposal(String threadId, String content) {
        add(threadId, new Entry(Kind.PROPOSED, content, Instant.now()));
    }

    public void rememberSent(String threadId, String content) {
        add(threadId, new Entry(Kind.SENT, content, Instant.now()));
    }

    public List<Entry> recent(String threadId, int maxMessages) {
        LinkedList<Entry> entries = threads.get(threadId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            int from = Math.max(0, entries.size() - maxMessages);
            return new ArrayList<>(entries.subList(from, entries.size()));
        }
    }

    public String format(String threadId, int maxMessages) {
        List<Entry> entries = recent(threadId, maxMessages);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Entry entry : entries) {
            builder.append("[").append(label(entry.kind())).append("] ")
                    .append(entry.content()).append("\n---\n");
        }
        return builder.toString();
    }

    private static String label(Kind kind) {
        return switch (kind) {
            case INCOMING -> "Email recu";
            case PROPOSED -> "Proposition generee (non envoyee)";
            case SENT -> "Email envoye par l'utilisateur";
        };
    }

    private void add(String threadId, Entry entry) {
        LinkedList<Entry> entries = threads.computeIfAbsent(threadId, key -> new LinkedList<>());
        synchronized (entries) {
            entries.add(entry);
        }
    }
}

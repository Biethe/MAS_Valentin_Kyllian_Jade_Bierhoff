package fr.mas.email.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the knowledge base about recipients from {@code context/recipients.json}.
 * The key is the recipient email address. The value holds the name, role,
 * organization and free-form notes that the assistant can use.
 *
 * <p>When a reply is sent, the reply is appended to the contact notes so the
 * knowledge base learns from the user's own answers.</p>
 */
public final class RecipientKnowledgeBase {

    private final Map<String, RecipientKnowledge> entries = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;

    public RecipientKnowledgeBase(Path path) {
        this.path = path;
        load(path);
    }

    private void load(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(path.toFile());
            root.fields().forEachRemaining(entry -> {
                String email = entry.getKey();
                JsonNode node = entry.getValue();
                entries.put(email.toLowerCase(), new RecipientKnowledge(
                        email,
                        node.path("name").asText(""),
                        node.path("role").asText(""),
                        node.path("organization").asText(""),
                        node.path("notes").asText("")));
            });
        } catch (IOException ignored) {
            // A missing or malformed knowledge base should not break the app.
        }
    }

    public RecipientKnowledge lookup(String sender) {
        String email = extractEmail(sender);
        if (email == null) {
            return unknown(sender);
        }
        RecipientKnowledge knowledge = entries.get(email.toLowerCase());
        if (knowledge != null) {
            return knowledge;
        }
        return unknown(sender);
    }

    public List<RecipientKnowledge> all() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Formats what is known about a sender for a system prompt. Returns an
     * empty string when the contact is unknown, so callers can append it
     * without adding noise to the prompt.
     */
    public String promptContextFor(String sender) {
        RecipientKnowledge knowledge = lookup(sender);
        if (knowledge == null || !isKnown(knowledge)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendIfUseful(builder, "Name", knowledge.name(), knowledge.email());
        appendIfUseful(builder, "Role", knowledge.role(), null);
        appendIfUseful(builder, "Organization", knowledge.organization(), null);
        appendIfUseful(builder, "Notes", knowledge.notes(), "No knowledge available for this contact.");
        return builder.toString();
    }

    private static boolean isKnown(RecipientKnowledge knowledge) {
        boolean knownRole = knowledge.role() != null && !"Unknown".equalsIgnoreCase(knowledge.role());
        boolean knownNotes = knowledge.notes() != null
                && !"No knowledge available for this contact.".equals(knowledge.notes())
                && !knowledge.notes().isBlank();
        return knownRole || knownNotes;
    }

    private static void appendIfUseful(StringBuilder builder, String label, String value, String ignore) {
        if (value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value)) {
            return;
        }
        if (ignore != null && ignore.equalsIgnoreCase(value)) {
            return;
        }
        builder.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    /**
     * Adds the reply to the knowledge base for the recipient. The reply is
     * appended to the notes so the next interaction can reuse it.
     */
    public synchronized void recordReply(String sender, String reply) {
        String email = extractEmail(sender);
        if (email == null || reply == null || reply.isBlank()) {
            return;
        }
        RecipientKnowledge existing = entries.get(email.toLowerCase());
        String baseNotes = existing == null ? "No knowledge yet." : existing.notes();
        String notes = baseNotes + "\n\nLast reply: " + reply.trim();
        RecipientKnowledge updated = new RecipientKnowledge(
                existing == null ? email : existing.email(),
                existing == null ? email : existing.name(),
                existing == null ? "Unknown" : existing.role(),
                existing == null ? "Unknown" : existing.organization(),
                notes);
        entries.put(email.toLowerCase(), updated);
        save();
    }

    private void save() {
        try {
            Files.createDirectories(path.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), entries);
        } catch (IOException ignored) {
            // The reply is still sent; the knowledge base update is best effort.
        }
    }

    private static String extractEmail(String sender) {
        if (sender == null) {
            return null;
        }
        Matcher matcher = Pattern.compile("<([^>]+)>").matcher(sender);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        String trimmed = sender.trim();
        return trimmed.contains("@") ? trimmed : null;
    }

    private static RecipientKnowledge unknown(String sender) {
        return new RecipientKnowledge(
                sender,
                sender,
                "Unknown",
                "Unknown",
                "No knowledge available for this contact.");
    }
}

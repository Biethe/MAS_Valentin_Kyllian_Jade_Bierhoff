package com.assistant.mail.agentic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Knowledge about people: one card per address in {@code CONTEXT_DIR/recipients.json}
 * ({"a@b.c": {"name", "role", "organization", "notes", "replies": [...]}}).
 * <ul>
 *   <li>the owner edits the cards (chat page, "Agents" panel);</li>
 *   <li>a card whose name or address appears in a request is put in the specialist's prompt;</li>
 *   <li>a reply the owner validates is remembered in the card of its recipient (the last three).</li>
 * </ul>
 * Cards are the owner's data: only the owner's actions change them, never an agent's own decision.
 */
final class People {
    static final Pattern EMAIL = Pattern.compile("[^@\\s\"<>]{1,64}@[^@\\s\"<>]{1,100}\\.[^@\\s\"<>]{2,20}");
    static final int MAX_REPLIES = 3, REPLY_CHARS = 400;
    private static final Set<String> GENERIC = Set.of("office", "university", "administration", "research", "newsletter",
            "google", "professor", "unknown", "mail", "team", "service");
    private static final Object LOCK = new Object(); // the chat page and the agents share the one file

    private People() {}

    private static Path file(Path dir) {
        return dir.resolve("recipients.json");
    }

    static ObjectNode read(Path dir) {
        synchronized (LOCK) {
            try {
                if (Files.isRegularFile(file(dir)) && Llm.JSON.readTree(Files.readString(file(dir))) instanceof ObjectNode o) return o;
            } catch (IOException | RuntimeException e) {
                // unreadable file: no cards
            }
            return Llm.JSON.createObjectNode();
        }
    }

    private static void write(Path dir, ObjectNode all) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(file(dir), Llm.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardCharsets.UTF_8);
    }

    /** Creates or replaces the owner-written fields of a card; the remembered replies are kept. */
    static void save(Path dir, String email, String name, String role, String organization, String notes) throws IOException {
        synchronized (LOCK) {
            ObjectNode all = read(dir);
            JsonNode old = all.path(email);
            ObjectNode card = Llm.JSON.createObjectNode();
            card.put("email", email).put("name", name.isBlank() ? email : name).put("role", role)
                    .put("organization", organization).put("notes", notes);
            if (old.path("replies").isArray()) card.set("replies", old.path("replies"));
            all.set(email, card);
            write(dir, all);
        }
    }

    static boolean delete(Path dir, String email) throws IOException {
        synchronized (LOCK) {
            ObjectNode all = read(dir);
            if (all.remove(email) == null) return false;
            write(dir, all);
            return true;
        }
    }

    /** Remembers a validated reply in the recipient's card (created if unknown); keeps the last three. */
    static void learn(Path dir, String email, String reply) throws IOException {
        synchronized (LOCK) {
            ObjectNode all = read(dir);
            ObjectNode card;
            if (all.path(email) instanceof ObjectNode existing) {
                card = existing;
            } else {
                card = Llm.JSON.createObjectNode();
                card.put("email", email).put("name", email).put("role", "Unknown").put("organization", "Unknown")
                        .put("notes", "Created from a validated reply.");
                all.set(email, card);
            }
            String text = reply.replaceAll("\\s+", " ").strip();
            if (text.length() > REPLY_CHARS) text = text.substring(0, REPLY_CHARS) + "...";
            ArrayNode replies = card.path("replies") instanceof ArrayNode a ? a : card.putArray("replies");
            replies.add(LocalDate.now() + ": " + text);
            while (replies.size() > MAX_REPLIES) replies.remove(0);
            write(dir, all);
        }
    }

    /** A card as a prompt line: who, role, the owner's notes and the replies the owner validated. */
    static String describe(JsonNode c, String key) {
        String email = c.path("email").asString(key);
        StringBuilder s = new StringBuilder(c.path("name").asString(email)).append(" <").append(email).append(">, ")
                .append(c.path("role").asString("")).append(", ").append(c.path("organization").asString("")).append(". ")
                .append(c.path("notes").asString("").replaceAll("\\s+", " "));
        for (JsonNode r : c.path("replies")) s.append(" Reply validated on ").append(r.asString("").replace('\n', ' '));
        return s.toString();
    }

    /** The cards of the people a request names (address, full name or a distinctive part of the name). */
    static List<String> mentioned(Path dir, String request) {
        String text = request.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (var entry : read(dir).properties()) {
            JsonNode c = entry.getValue();
            String email = c.path("email").asString(entry.getKey()).toLowerCase(Locale.ROOT);
            boolean hit = text.contains(email);
            for (String part : c.path("name").asString("").toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
                if (hit) break;
                hit = part.length() >= 4 && !GENERIC.contains(part)
                        && Pattern.compile("(?<![\\p{L}])" + Pattern.quote(part) + "(?![\\p{L}])").matcher(text).find();
            }
            if (hit) out.add(describe(c, entry.getKey()));
        }
        return out;
    }
}

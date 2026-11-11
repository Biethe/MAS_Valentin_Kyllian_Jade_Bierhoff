package com.assistant.mail.workflow;

import com.assistant.mail.model.mail.Email;
import com.assistant.mail.service.Progress;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Keyword search stays in the planner. This ranks snippets by meaning and resolves latest or oldest. */
public final class MailIndex {
    private static final Path FILE = Path.of("mail-index.jsonl");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final int PAGE = 20;
    private static final float FLOOR = 0.25f;
    // ponytail: one inbox page per turn. Page further only if a query needs mail older than this page.

    public record Found(Email email, String blocked) {}

    private MailIndex() {}

    /** Null when the instruction is not about the latest or the oldest mail. */
    public static Found order(MailClientMcp mail, String body) {
        Order kind = orderOf(body);
        if (kind == Order.NONE) return null;
        Progress.note(kind == Order.LATEST ? "Looking up the latest mail" : "Looking up the oldest mail");
        try {
            if (kind == Order.LATEST) {
                List<Email> hits = mail.searchMail("in:inbox");
                if (hits == null || hits.isEmpty()) hits = mail.searchMail("in:anywhere");
                if (hits == null || hits.isEmpty()) return new Found(null, "No latest mail.");
                return new Found(open(mail, hits.get(0)), null);
            }
            Email oldest = oldest(mail::searchMail, LocalDate.of(1990, 1, 1), LocalDate.now().plusDays(1));
            if (oldest == null) return new Found(null, "No oldest mail.");
            return new Found(open(mail, oldest), null);
        } catch (RuntimeException e) {
            Progress.note("mail order error " + e.getMessage());
            return new Found(null, e.getMessage() == null ? "Mail search failed." : e.getMessage());
        }
    }

    /** The nearest indexed snippet, or null when embeddings are off or nothing is close. */
    public static Email nearest(MailClientMcp mail, PlannerAgentClient agent, String instruction) {
        try {
            fill(mail, agent);
            Progress.note("Matching the request by meaning");
            float[] query = agent.embed(instruction);
            if (query.length == 0) return null;
            Entry best = null;
            float bestScore = FLOOR;
            for (Entry entry : load().values()) {
                float score = cosine(query, entry.vector);
                if (score > bestScore) {
                    bestScore = score;
                    best = entry;
                }
            }
            if (best == null) return null;
            Progress.note("Matched a mail by meaning");
            return mail.readMail(best.id);
        } catch (RuntimeException e) {
            Progress.note("semantic error " + e.getMessage());
            return null;
        }
    }

    static boolean exact(String text) {
        if (text == null) return false;
        return text.indexOf('"') >= 0 || text.indexOf('«') >= 0
                || text.matches("(?s).*[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}.*");
    }

    static Order orderOf(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (t.contains("oldest") || t.contains("earliest") || t.contains("first mail")
                || t.contains("first email") || t.contains("very first") || t.contains("premier mail")
                || t.contains("premier message") || t.contains("tout premier") || t.contains("plus ancien")) {
            return Order.OLDEST;
        }
        if (t.contains("latest") || t.contains("newest") || t.contains("last mail") || t.contains("last email")
                || t.contains("most recent") || t.contains("dernier mail") || t.contains("dernier message")
                || t.contains("plus récent") || t.contains("plus recent")) {
            return Order.LATEST;
        }
        return Order.NONE;
    }

    static String current(String text) {
        if (text == null) return "";
        String mark = "Current request:\n";
        int at = text.lastIndexOf(mark);
        return at < 0 ? text.strip() : text.substring(at + mark.length()).strip();
    }

    /** A follow-up about the attachment of the mail already under discussion. */
    static boolean fileOfLastMail(String current, String all) {
        String c = current == null ? "" : current.toLowerCase(Locale.ROOT);
        String full = all == null ? "" : all.toLowerCase(Locale.ROOT);
        boolean file = c.contains("file") || c.contains("attachment") || c.contains("fichier");
        boolean last = c.contains("last mail") || c.contains("last email") || c.contains("dernier mail")
                || full.contains("last mail") || full.contains("last email") || full.contains("dernier mail");
        return file && last;
    }

    enum Order { LATEST, OLDEST, NONE }

    /** Newest hit on the earliest day Gmail still returns. {@code before:} is newest-first, so several mails that day keep the newest of them. */
    static Email oldest(Function<String, List<Email>> search, LocalDate low, LocalDate high) {
        Email found = null;
        for (int step = 0; step < 16 && low.isBefore(high); step++) {
            long span = ChronoUnit.DAYS.between(low, high);
            LocalDate mid = low.plusDays(Math.max(1, span / 2));
            if (!mid.isBefore(high)) mid = low;
            List<Email> hits = search.apply("before:" + mid.format(DAY));
            if (hits == null || hits.isEmpty()) {
                low = mid.plusDays(1);
            } else {
                found = hits.get(0);
                high = mid;
            }
        }
        return found;
    }

    static float cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) return -1f;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * (double) right[i];
            leftNorm += left[i] * (double) left[i];
            rightNorm += right[i] * (double) right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) return -1f;
        return (float) (dot / Math.sqrt(leftNorm * rightNorm));
    }

    private static Email open(MailClientMcp mail, Email hit) {
        if (hit == null || hit.getId() == null) return hit;
        try {
            Email full = mail.readMail(Long.toString(hit.getId()));
            return full == null ? hit : full;
        } catch (RuntimeException e) {
            Progress.note("mail read error " + e.getMessage());
            return hit;
        }
    }

    private static void fill(MailClientMcp mail, PlannerAgentClient agent) {
        List<Email> page;
        try {
            page = mail.searchMail("in:inbox");
        } catch (RuntimeException e) {
            Progress.note("mail index error " + e.getMessage());
            return;
        }
        if (page == null || page.isEmpty()) return;
        Map<String, Entry> have = load();
        int added = 0;
        for (Email email : page) {
            if (added >= PAGE) break;
            String id = mail.remoteId(email.getId());
            if (id.isBlank() || have.containsKey(id)) continue;
            String text = snippet(email);
            float[] vector = agent.embed(text);
            if (vector.length == 0) return;
            append(id, text, vector);
            have.put(id, new Entry(id, text, vector));
            added++;
        }
    }

    private static String snippet(Email email) {
        String subject = email.getSubject() == null ? "" : email.getSubject().strip();
        String body = email.getBody() == null ? "" : email.getBody().strip();
        if (body.length() > 500) body = body.substring(0, 500);
        return subject.isEmpty() ? body : subject + "\n" + body;
    }

    private static Map<String, Entry> load() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (!Files.exists(FILE)) return entries;
        try {
            for (String line : Files.readAllLines(FILE)) {
                if (line.isBlank()) continue;
                JsonNode node = JSON.readTree(line);
                JsonNode vector = node.path("vector");
                float[] values = new float[vector.size()];
                for (int i = 0; i < vector.size(); i++) values[i] = (float) vector.get(i).asDouble();
                String id = node.path("id").asString("");
                if (!id.isBlank() && values.length > 0) entries.put(id, new Entry(id, node.path("text").asString(""), values));
            }
        } catch (Exception e) {
            throw new IllegalStateException("unreadable " + FILE, e);
        }
        return entries;
    }

    private static void append(String id, String text, float[] vector) {
        ObjectNode node = JSON.createObjectNode().put("id", id).put("text", text);
        ArrayNode values = node.putArray("vector");
        for (float value : vector) values.add(value);
        try {
            Files.writeString(FILE, node + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write " + FILE, e);
        }
    }

    private record Entry(String id, String text, float[] vector) {}

    public static void main(String[] args) {
        if (!(cosine(new float[] {1, 0}, new float[] {1, 0}) > cosine(new float[] {1, 0}, new float[] {0, 1}))) {
            throw new IllegalStateException("cosine");
        }
        if (orderOf("Summarize my very first mail") != Order.OLDEST) throw new IllegalStateException("oldest");
        if (orderOf("Can you summarize my last mail?") != Order.LATEST) throw new IllegalStateException("last mail");
        if (orderOf("Réponds à Ada") != Order.NONE) throw new IllegalStateException("none");
        if (!exact("write to ada@university.edu")) throw new IllegalStateException("exact");
        if (exact("the mail about the grant")) throw new IllegalStateException("meaning");
        Email oldest = oldest(query -> {
            LocalDate day = LocalDate.parse(query.substring("before:".length()), DAY);
            if (!day.isAfter(LocalDate.of(2015, 6, 1))) return List.of();
            Email email = new Email();
            email.setId(1L);
            email.setSubject(query);
            return List.of(email);
        }, LocalDate.of(1990, 1, 1), LocalDate.of(2020, 1, 1));
        if (oldest == null || !oldest.getSubject().equals("before:2015/06/02")) {
            throw new IllegalStateException(oldest == null ? "no oldest" : oldest.getSubject());
        }
        System.out.println("MailIndex ok");
    }
}

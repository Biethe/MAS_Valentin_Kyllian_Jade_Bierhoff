package com.assistant.mail.agentic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Private documents of each specialist, and the owner's contact cards. Off unless CONTEXT_DIR points to a folder:
 * <pre>
 *   CONTEXT_DIR/student/*.txt|md      documents only the student specialist receives
 *   CONTEXT_DIR/teacher/...           (one sub-folder per specialist name)
 *   CONTEXT_DIR/recipients.json       {"a@b.c": {"name", "role", "organization", "notes"}}
 * </pre>
 * Each file is cut into passages of about 600 characters. For a request, the closest passages of the chosen
 * specialist are returned: by cosine similarity of embeddings when the model provides them, otherwise by shared words.
 * Other specialists never see these passages (each agent has its own context).
 */
final class ProfileContext {
    private static final int CHUNK = 600;

    private final Path dir;
    private final Llm llm; // may be null: word overlap only
    private final Map<String, List<String>> chunks = new HashMap<>();
    private final Map<String, List<double[]>> vectors = new HashMap<>();

    ProfileContext(Path dir, Llm llm) {
        this.dir = dir;
        this.llm = llm;
    }

    /** The context folder named by CONTEXT_DIR, or null (feature off). */
    static ProfileContext fromEnv(Llm llm) {
        String value = System.getenv("CONTEXT_DIR");
        if (value == null || value.isBlank() || !Files.isDirectory(Path.of(value))) return null;
        return new ProfileContext(Path.of(value), llm);
    }

    /** At most {@code k} passages of this specialist's folder that match the request, best first. */
    synchronized List<String> passages(String specialist, String request, int k) {
        List<String> all = chunks.computeIfAbsent(specialist, this::load);
        if (all.isEmpty()) return List.of();
        double[] scores = new double[all.size()];
        double[] query = llm == null ? null : llm.embed(request);
        List<double[]> vecs = query == null ? null : vectors.computeIfAbsent(specialist, name -> embedAll(all));
        if (query != null && vecs != null && vecs.size() == all.size()) {
            for (int i = 0; i < scores.length; i++) scores[i] = cosine(query, vecs.get(i));
        } else {
            Set<String> wanted = words(request);
            for (int i = 0; i < scores.length; i++) {
                Set<String> have = words(all.get(i));
                for (String w : wanted) if (have.contains(w)) scores[i]++;
            }
        }
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(k, order.length); i++) if (scores[order[i]] > 0) out.add(all.get(order[i]));
        return out;
    }

    /** Contact cards matching a name (surname, first name or address), as lookup_contact prints them; null if none. */
    String contact(String name) {
        Path file = dir.resolve("recipients.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonNode all = Llm.JSON.readTree(Files.readString(file));
            StringBuilder s = new StringBuilder();
            for (String part : name.toLowerCase(Locale.ROOT).split("[^\\p{L}@.]+")) {
                if (part.length() < 3) continue;
                for (var entry : all.properties()) {
                    JsonNode c = entry.getValue();
                    String email = c.path("email").asString(entry.getKey());
                    String hay = (c.path("name").asString("") + " " + email).toLowerCase(Locale.ROOT);
                    if (hay.contains(part) && s.indexOf(email) < 0) {
                        s.append(c.path("name").asString(email)).append(" <").append(email).append("> role: ")
                                .append(c.path("role").asString("")).append(", ").append(c.path("organization").asString(""))
                                .append(". ").append(c.path("notes").asString("")).append('\n');
                    }
                }
            }
            return s.isEmpty() ? null : s.toString();
        } catch (IOException | RuntimeException e) {
            return null; // an unreadable card file means no cards
        }
    }

    private List<String> load(String specialist) {
        List<String> out = new ArrayList<>();
        Path folder = dir.resolve(specialist);
        if (!Files.isDirectory(folder)) return out;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".txt") || p.toString().endsWith(".md")).sorted().toList()) {
                out.addAll(split(Files.readString(f)));
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private List<double[]> embedAll(List<String> texts) {
        List<double[]> out = new ArrayList<>();
        for (String t : texts) {
            double[] v = llm.embed(t);
            if (v == null) return null; // no embedding model: word overlap
            out.add(v);
        }
        return out;
    }

    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (cur.length() > 0 && cur.length() + p.length() > CHUNK) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(p).append("\n\n");
        }
        if (cur.length() > 0) out.add(cur.toString().strip());
        return out;
    }

    static Set<String> words(String text) {
        String flat = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        Set<String> out = new HashSet<>();
        for (String w : flat.split("[^a-z0-9]+")) if (w.length() >= 3) out.add(w);
        return out;
    }

    static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}

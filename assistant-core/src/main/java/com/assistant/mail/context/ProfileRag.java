package com.assistant.mail.context;

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

/**
 * Retrieval over a folder with one sub-folder per profile ({@code student/}, {@code teacher/},
 * {@code administration/}, {@code research/}...). Each .txt/.md file is cut into passages; the passages
 * closest to the query are returned. Embeddings are used when available, otherwise word overlap.
 */
public final class ProfileRag {
    private static final int CHUNK = 600;

    private record Chunk(String profile, String source, String text) {}

    private static final class Index {
        final List<Chunk> chunks = new ArrayList<>();
        double[][] vectors; // null when embedding failed
    }

    private final Path root;
    private final Embedder embedder;
    private final Map<String, Index> indexes = new HashMap<>();

    public ProfileRag(Path root, Embedder embedder) {
        this.root = root;
        this.embedder = embedder;
    }

    /** Profiles present on disk (sub-folder names). */
    public List<String> profiles() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The best {@code k} passages of one profile, or of every profile when {@code profile} is null. */
    public List<String> search(String profile, String query, int k) {
        List<String> targets = profile == null ? profiles() : List.of(profile);
        record Scored(double score, Chunk chunk) {}
        List<Scored> all = new ArrayList<>();
        for (String name : targets) {
            Index index = index(name);
            if (index.chunks.isEmpty()) continue;
            double[] scores = scores(index, query);
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] > 0) all.add(new Scored(scores[i], index.chunks.get(i)));
            }
        }
        all.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<String> out = new ArrayList<>();
        for (Scored s : all.subList(0, Math.min(k, all.size()))) {
            out.add("[" + s.chunk.profile + "/" + s.chunk.source + "] " + s.chunk.text);
        }
        return out;
    }

    private synchronized Index index(String profile) {
        return indexes.computeIfAbsent(profile, this::build);
    }

    private Index build(String profile) {
        Index index = new Index();
        Path dir = root.resolve(profile);
        if (!Files.isDirectory(dir)) return index;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".txt") || f.toString().endsWith(".md"))
                    .sorted().toList()) {
                for (String text : split(Files.readString(file))) {
                    index.chunks.add(new Chunk(profile, file.getFileName().toString(), text));
                }
            }
        } catch (IOException e) {
            return index;
        }
        if (embedder != null && !index.chunks.isEmpty()) {
            try {
                index.vectors = embedder.embed(index.chunks.stream().map(Chunk::text).toList());
            } catch (RuntimeException e) {
                index.vectors = null; // fall back to words
            }
        }
        return index;
    }

    private double[] scores(Index index, String query) {
        double[] out = new double[index.chunks.size()];
        if (index.vectors != null && embedder != null) {
            try {
                double[][] embedded = embedder.embed(List.of(query));
                double[] q = embedded.length == 0 ? null : embedded[0];
                if (q != null) {
                    for (int i = 0; i < out.length; i++) out[i] = cosine(q, index.vectors[i]);
                    return out;
                }
            } catch (RuntimeException e) {
                // fall through to words
            }
        }
        Set<String> wanted = words(query);
        for (int i = 0; i < out.length; i++) {
            Set<String> have = words(index.chunks.get(i).text);
            int hits = 0;
            for (String w : wanted) if (have.contains(w)) hits++;
            out[i] = hits;
        }
        return out;
    }

    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.strip().split("\\R\\s*\\R")) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (current.length() > 0 && current.length() + p.length() > CHUNK) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(p).append("\n\n");
        }
        if (current.length() > 0) chunks.add(current.toString().strip());
        return chunks;
    }

    static Set<String> words(String text) {
        String flat = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
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

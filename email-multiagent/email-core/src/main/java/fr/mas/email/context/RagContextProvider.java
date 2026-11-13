package fr.mas.email.context;

import fr.mas.email.model.EmailCategory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Retrieval-augmented context provider. It indexes the context documents of
 * each category, embeds them, and returns the chunks most similar to the
 * email. If the embedding model is unavailable it falls back to the whole
 * category context.
 */
public final class RagContextProvider implements ContextProvider {

    private static final int MAX_CHUNK_CHARS = 220;

    private final Path baseDirectory;
    private final EmbeddingClient embeddingClient;
    private final FileContextProvider fallback;
    private final Map<EmailCategory, List<Chunk>> index = new ConcurrentHashMap<>();
    private final AtomicBoolean fallbackWarned = new AtomicBoolean();
    private final int topK;

    public RagContextProvider(Path baseDirectory, EmbeddingClient embeddingClient, int topK) {
        this.baseDirectory = baseDirectory;
        this.embeddingClient = embeddingClient;
        this.fallback = new FileContextProvider(baseDirectory);
        this.topK = topK;
    }

    @Override
    public String contextFor(EmailCategory category) {
        return fallback.contextFor(category);
    }

    @Override
    public String contextFor(EmailCategory category, String query) {
        if (query == null || query.isBlank()) {
            return fallback.contextFor(category);
        }
        List<Chunk> chunks = index.computeIfAbsent(category, this::buildIndex);
        if (chunks.isEmpty()) {
            return fallback.contextFor(category);
        }
        try {
            float[] queryVector = embeddingClient.embed(query);
            return chunks.stream()
                    .sorted(Comparator.comparingDouble((Chunk chunk) -> -cosine(queryVector, chunk.vector())))
                    .limit(topK)
                    .map(Chunk::text)
                    .collect(Collectors.joining("\n\n"));
        } catch (Exception e) {
            warnFallback("query embedding failed: " + e.getMessage());
            return fallback.contextFor(category);
        }
    }

    private List<Chunk> buildIndex(EmailCategory category) {
        Path directory = baseDirectory.resolve(category.name().toLowerCase(java.util.Locale.ROOT));
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Chunk> chunks = new ArrayList<>();
        boolean embeddingFailed = false;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String chunk : splitIntoChunks(text)) {
                    try {
                        chunks.add(new Chunk(chunk, embeddingClient.embed(chunk)));
                    } catch (Exception ignored) {
                        // A failed chunk should not prevent the others from being indexed.
                        embeddingFailed = true;
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        if (chunks.isEmpty() && embeddingFailed) {
            warnFallback("embedding model unavailable; using the full category context");
        }
        return chunks;
    }

    private void warnFallback(String reason) {
        if (fallbackWarned.compareAndSet(false, true)) {
            System.err.println("[RAG] " + reason + ". Set rag.embedding.model to an installed Ollama model.");
        }
    }

    /**
     * Splits a document on blank lines, then packs consecutive lines into
     * chunks of at most {@value #MAX_CHUNK_CHARS} characters. This keeps
     * bullets and wrapped prose together while producing several retrievable
     * units per file.
     */
    static List<String> splitIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            StringBuilder buffer = new StringBuilder();
            for (String line : paragraph.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (buffer.length() > 0 && buffer.length() + 1 + trimmed.length() > MAX_CHUNK_CHARS) {
                    chunks.add(buffer.toString());
                    buffer.setLength(0);
                }
                if (buffer.length() > 0) {
                    buffer.append(' ');
                }
                buffer.append(trimmed);
            }
            if (buffer.length() > 0) {
                chunks.add(buffer.toString());
            }
        }
        return chunks;
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record Chunk(String text, float[] vector) {
    }
}

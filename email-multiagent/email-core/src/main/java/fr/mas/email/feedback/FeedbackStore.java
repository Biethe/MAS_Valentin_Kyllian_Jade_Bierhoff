package fr.mas.email.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Records user decisions (accept, reject, correct) and exposes the resulting
 * acceptance rate. The router can use this rate to raise its confidence bar
 * for categories that are often rejected.
 */
public final class FeedbackStore {

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeedbackStore(Path directory) {
        this.directory = directory;
    }

    public synchronized void record(FeedbackEntry entry) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("feedback-" + LocalDate.now() + ".jsonl");
            String line = mapper.writeValueAsString(entry) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Feedback must never break the request path.
        }
    }

    public List<FeedbackEntry> all() {
        List<FeedbackEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        try {
                            entries.add(mapper.readValue(line, FeedbackEntry.class));
                        } catch (Exception ignored) {
                            // Skip malformed lines.
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Return what we have.
        }
        return entries;
    }

    public double acceptanceRate(String category) {
        int accepted = 0;
        int total = 0;
        for (FeedbackEntry entry : all()) {
            if (category != null && !category.equalsIgnoreCase(entry.category())) {
                continue;
            }
            total++;
            if ("accept".equalsIgnoreCase(entry.action())) {
                accepted++;
            }
        }
        return total == 0 ? 1.0 : (double) accepted / total;
    }

    public double rejectionRate(String category) {
        return 1.0 - acceptanceRate(category);
    }

    public String categoryKey(String category) {
        return category == null ? "" : category.toLowerCase(Locale.ROOT);
    }
}

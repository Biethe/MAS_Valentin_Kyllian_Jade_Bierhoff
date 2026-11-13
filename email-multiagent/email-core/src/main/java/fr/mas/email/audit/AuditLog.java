package fr.mas.email.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends audit events to JSON Lines files, one file per day. The log is
 * append-only and never rewrites history.
 */
public final class AuditLog {

    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditLog(Path directory) {
        this.directory = directory;
    }

    public synchronized void record(AuditEntry entry) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("audit-" + LocalDate.now() + ".jsonl");
            String line = mapper.writeValueAsString(entry) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Audit logging must never break the request path.
        }
    }

    public List<AuditEntry> all() {
        List<AuditEntry> entries = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return entries;
        }
        try (var stream = Files.list(directory)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        try {
                            entries.add(mapper.readValue(line, AuditEntry.class));
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
}

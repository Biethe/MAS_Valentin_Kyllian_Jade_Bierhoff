package com.assistant.mail.workflow.shared;

import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.MemoryRule;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** One JSON file of long-term rules. No file means an empty memory and nothing is written. */
public final class MemoryFile {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MemoryFile() {}

    public static LongTermMemory open() {
        String file = System.getenv("MEMORY_FILE");
        if (file == null || file.isBlank()) return new LongTermMemory();
        return load(Path.of(file));
    }

    public static LongTermMemory load(Path path) {
        if (path == null || !Files.exists(path)) return new LongTermMemory();
        try {
            LongTermMemory memory = JSON.readValue(path.toFile(), LongTermMemory.class);
            return memory == null || memory.getRules() == null ? new LongTermMemory() : memory;
        } catch (RuntimeException e) {
            throw new IllegalStateException("unreadable memory " + path, e);
        }
    }

    public static void save(Path path, LongTermMemory memory) {
        if (path == null) return;
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            JSON.writeValue(path.toFile(), memory == null ? new LongTermMemory() : memory);
        } catch (Exception e) {
            throw new IllegalStateException("cannot write memory " + path, e);
        }
    }

    /** Writes only when {@code MEMORY_FILE} is set, so benchmark scenarios stay independent. */
    public static void saveIfSet(LongTermMemory memory) {
        String file = System.getenv("MEMORY_FILE");
        if (file == null || file.isBlank()) return;
        save(Path.of(file), memory);
    }

    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile("memory", ".json");
        LongTermMemory memory = new LongTermMemory();
        MemoryRule rule = new MemoryRule();
        rule.setText("Ne retiens un fait que s'il est dans un document lu.");
        rule.setVetoed(true);
        memory.getRules().add(rule);
        save(path, memory);
        LongTermMemory loaded = load(path);
        assert loaded.getRules().size() == 1;
        assert loaded.getRules().get(0).getText().equals(rule.getText());
        assert Boolean.TRUE.equals(loaded.getRules().get(0).getVetoed());
        assert load(path.resolveSibling("missing-memory.json")).getRules().isEmpty();
        Files.deleteIfExists(path);
        System.out.println("MemoryFile ok");
    }
}

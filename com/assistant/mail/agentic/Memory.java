package com.assistant.mail.agentic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Long-term memory of lessons, one list per agent (Strategy: no memory, or a JSON file).
 * A lesson is stored only when an agent lost an argument, i.e. conceded to the opponent: the friction is the signal.
 */
public interface Memory {
    List<String> recall(String agent, int k);

    void learn(String agent, String lesson);

    default void save() {}

    static Memory fromEnv() {
        String f = System.getenv("MEMORY_FILE");
        return f == null || f.isBlank() ? new None() : new JsonFile(Path.of(f));
    }

    final class None implements Memory {
        public List<String> recall(String agent, int k) { return List.of(); }
        public void learn(String agent, String lesson) {}
    }

    /** Newest lessons first, exact duplicates skipped. ponytail: no scoring or merging, add when the budget overflows. */
    final class JsonFile implements Memory {
        private final Path path;
        private final ObjectNode root;

        JsonFile(Path path) {
            this.path = path;
            try {
                root = Files.exists(path) ? (ObjectNode) Llm.JSON.readTree(path.toFile()) : Llm.JSON.createObjectNode();
            } catch (RuntimeException e) {
                throw new IllegalStateException("unreadable memory " + path, e);
            }
        }

        public List<String> recall(String agent, int k) {
            List<String> out = new ArrayList<>();
            JsonNode list = root.path(agent);
            for (int i = list.size() - 1; i >= 0 && out.size() < k; i--) out.add(list.get(i).path("lesson").asString());
            return out;
        }

        public void learn(String agent, String lesson) {
            if (lesson == null || lesson.isBlank()) return;
            ArrayNode list = root.has(agent) ? (ArrayNode) root.get(agent) : root.putArray(agent);
            for (JsonNode l : list) if (l.path("lesson").asString().equalsIgnoreCase(lesson.strip())) return;
            list.addObject().put("lesson", lesson.strip()).put("at", java.time.Instant.now().toString());
        }

        public void save() {
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                Files.writeString(path, root.toPrettyString());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot write memory " + path, e);
            }
        }
    }
}

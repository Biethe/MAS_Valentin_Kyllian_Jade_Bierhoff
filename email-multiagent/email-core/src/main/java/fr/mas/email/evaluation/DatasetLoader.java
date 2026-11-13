package fr.mas.email.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.mas.email.model.EmailCategory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads a JSONL dataset of labeled emails. */
public final class DatasetLoader {

    private static final String CLASSPATH_DATASET = "/evaluation/emails.jsonl";

    private DatasetLoader() {
    }

    public static List<EvaluationCase> loadFromClasspath() {
        try (InputStream in = DatasetLoader.class.getResourceAsStream(CLASSPATH_DATASET)) {
            if (in == null) {
                throw new IllegalStateException("Dataset not found: " + CLASSPATH_DATASET);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read dataset", e);
        }
    }

    public static List<EvaluationCase> loadFromFile(Path path) {
        try {
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read dataset: " + path, e);
        }
    }

    private static List<EvaluationCase> parse(String content) {
        ObjectMapper mapper = new ObjectMapper();
        List<EvaluationCase> cases = new ArrayList<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                String expected = node.path("expected").asText().trim().toUpperCase();
                if (expected.isEmpty()) {
                    continue;
                }
                cases.add(new EvaluationCase(
                        node.path("sender").asText("unknown@example.com"),
                        node.path("recipient").asText("assistant@example.com"),
                        node.path("subject").asText(""),
                        node.path("body").asText(""),
                        EmailCategory.valueOf(expected)));
            } catch (IOException e) {
                throw new IllegalStateException("Invalid dataset line: " + line, e);
            }
        }
        return cases;
    }
}

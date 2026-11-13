package com.assistant.mail.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.AssistantConfig;
import com.assistant.mail.workflow.Turn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class BenchSystemTest {
    private static Assistant scripted() {
        return Assistant.create(AssistantConfig.parse(new String[] {"--mock"}, Map.of()));
    }

    @Test void draftOutcomeFollowsTheTraceContract(@TempDir Path dir) throws Exception {
        Turn.Outcome o = scripted().ask("Réponds à Ada");
        Path out = dir.resolve("trace.jsonl");
        BenchSystem.write(out, "Réponds à Ada", o, "scripted", 5);
        List<String> lines = Files.readAllLines(out);
        JsonNode first = BenchSystem.read(lines.get(0));
        assertEquals("route", first.path("layer").asString());
        assertEquals("REPLY", first.path("output").path("instructions").get(0).path("type").asString());
        JsonNode last = BenchSystem.read(lines.get(lines.size() - 1));
        assertEquals("output", last.path("layer").asString());
        assertEquals("ada@u.test", last.path("output").path("draft").path("to").get(0).asString());
        assertEquals(lines.size(), last.path("step").asInt());
    }

    @Test void summaryAndQuestionOutcomes(@TempDir Path dir) throws Exception {
        Assistant a = scripted();
        JsonNode s = lastStep(dir, "Résume le mail", a.ask("Résume le mail"));
        assertTrue(s.path("output").path("summary").asString().contains("Réunion"));
        JsonNode q = lastStep(dir, "fais un truc", a.ask("fais un truc"));
        assertTrue(q.path("output").has("question"));
    }

    private static JsonNode lastStep(Path dir, String request, Turn.Outcome o) throws Exception {
        Path out = dir.resolve(Math.abs(request.hashCode()) + ".jsonl");
        BenchSystem.write(out, request, o, "scripted", 1);
        List<String> lines = Files.readAllLines(out);
        return BenchSystem.read(lines.get(lines.size() - 1));
    }
}

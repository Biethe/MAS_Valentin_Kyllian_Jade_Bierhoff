package com.assistant.mail.agentic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/** Self-check (run with java -ea): per-specialist documents, passage choice, isolation and contact cards. */
public final class ProfileContextCheck {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ctx");
        Files.createDirectories(dir.resolve("teacher"));
        Files.createDirectories(dir.resolve("student"));
        Files.writeString(dir.resolve("teacher/t.txt"),
                "Appointments are booked by mail, room B204.\n\nThe thesis jury sits in June.");
        Files.writeString(dir.resolve("student/s.txt"), "Exam registration closes on 15 January.");
        Files.writeString(dir.resolve("recipients.json"), """
                {"ada@u.test": {"email": "ada@u.test", "name": "Ada Lovelace", "role": "Professor",
                                "organization": "University", "notes": "Prefers formal French."}}
                """);

        ProfileContext words = new ProfileContext(dir, null);
        List<String> teacher = words.passages("teacher", "book an appointment in a room", 3);
        assert !teacher.isEmpty() && teacher.get(0).contains("B204") : teacher;
        assert words.passages("student", "exam registration", 3).get(0).contains("15 January");
        assert words.passages("student", "appointment room", 3).isEmpty() : "teacher text must not reach the student";
        assert words.passages("generic", "anything", 3).isEmpty() : "no folder means no context";
        assert words.passages("teacher", "zzz unknown", 3).isEmpty();

        // embeddings: the passage about the jury and the query point the same way, so it wins over word overlap
        Llm fake = new Llm() {
            public JsonNode chat(ArrayNode m, ArrayNode t, boolean j) { throw new UnsupportedOperationException(); }
            @Override public double[] embed(String text) {
                return text.toLowerCase().contains("jury") ? new double[] {1, 0} : new double[] {0, 1};
            }
        };
        ProfileContext vectors = new ProfileContext(dir, fake);
        assert vectors.passages("teacher", "when does the jury meet", 1).get(0).contains("jury");

        String card = words.contact("Lovelace");
        assert card != null && card.contains("ada@u.test") && card.contains("formal French") : card;
        assert words.contact("nobody here") == null;
        assert new ProfileContext(Files.createTempDirectory("empty"), null).contact("Ada") == null;

        assert ProfileContext.split("a\n\nb").size() == 1;
        assert ProfileContext.split("x".repeat(500) + "\n\n" + "y".repeat(500)).size() == 2;
        if (System.getenv("CONTEXT_DIR") == null) assert ProfileContext.fromEnv(null) == null : "off by default";
        System.out.println("ProfileContext ok");
    }
}

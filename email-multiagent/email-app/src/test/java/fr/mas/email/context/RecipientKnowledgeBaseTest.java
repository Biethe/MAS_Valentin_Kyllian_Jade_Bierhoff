package fr.mas.email.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipientKnowledgeBaseTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsAndRecordsReplies() throws Exception {
        Path file = tempDir.resolve("recipients.json");
        Files.writeString(file, """
                {
                  "prof@univ.fr": {
                    "name": "Prof. Martin",
                    "role": "Professor",
                    "organization": "University",
                    "notes": "Prefers concise replies."
                  }
                }
                """);

        RecipientKnowledgeBase base = new RecipientKnowledgeBase(file);
        RecipientKnowledge knowledge = base.lookup("Prof. Martin <prof@univ.fr>");
        assertEquals("Prof. Martin", knowledge.name());
        assertEquals("Professor", knowledge.role());

        base.recordReply("prof@univ.fr", "I will send the report by Friday.");

        RecipientKnowledge updated = base.lookup("prof@univ.fr");
        assertTrue(updated.notes().contains("I will send the report by Friday."));
        assertEquals(1, base.all().size());
    }

    @Test
    void returnsUnknownForUnknownContacts() {
        RecipientKnowledgeBase base = new RecipientKnowledgeBase(tempDir.resolve("missing.json"));
        RecipientKnowledge knowledge = base.lookup("random@example.com");
        assertEquals("Unknown", knowledge.role());
        assertEquals("No knowledge available for this contact.", knowledge.notes());
    }
}

package fr.mas.email.context;

import fr.mas.email.agent.SummaryAgent;
import fr.mas.email.agent.specialized.StudentAgent;
import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipientKnowledgePromptTest {

    @TempDir
    Path tempDir;

    private RecipientKnowledgeBase knowledgeBase() throws Exception {
        Path file = tempDir.resolve("recipients.json");
        Files.writeString(file, """
                {
                  "prof@univ.fr": {
                    "email": "prof@univ.fr",
                    "name": "Prof. Martin",
                    "role": "Professor",
                    "organization": "University",
                    "notes": "Prefers concise replies."
                  }
                }
                """);
        return new RecipientKnowledgeBase(file);
    }

    private static String systemPrompt(List<List<ChatMessage>> captured) {
        return captured.get(0).stream()
                .filter(message -> "system".equals(message.getRole()))
                .findFirst()
                .orElseThrow()
                .getContent();
    }

    @Test
    void promptContextContainsKnownContactAndIsEmptyForUnknown() throws Exception {
        RecipientKnowledgeBase base = knowledgeBase();

        String known = base.promptContextFor("Prof. Martin <prof@univ.fr>");
        assertTrue(known.contains("Name: Prof. Martin"));
        assertTrue(known.contains("Notes: Prefers concise replies."));

        assertTrue(base.promptContextFor("stranger@example.com").isBlank());
    }

    @Test
    void specialistPromptReceivesContactKnowledge() throws Exception {
        List<List<ChatMessage>> captured = new ArrayList<>();
        LLMClient llm = messages -> {
            captured.add(messages);
            return "Draft";
        };

        StudentAgent agent = new StudentAgent(
                llm, category -> "", new PromptRepository(), knowledgeBase());
        agent.handle(new EmailMessage("Prof. Martin <prof@univ.fr>", "me@example.com",
                "Question about the course", "What is the deadline?", "t1"), List.of());

        String system = systemPrompt(captured);
        assertTrue(system.contains("## Contact knowledge"));
        assertTrue(system.contains("Prefers concise replies."));
    }

    @Test
    void summaryPromptReceivesContactKnowledge() throws Exception {
        List<List<ChatMessage>> captured = new ArrayList<>();
        LLMClient llm = messages -> {
            captured.add(messages);
            return "{\"summary\":\"Asks about the deadline.\",\"needs_reply\":true,\"draft\":\"Friday.\"}";
        };

        SummaryAgent agent = new SummaryAgent(
                llm, category -> "", new PromptRepository(), knowledgeBase());
        agent.summarize(new EmailMessage("prof@univ.fr", "me@example.com",
                "Deadline", "When is the deadline?", "t1"));

        assertTrue(systemPrompt(captured).contains("Prefers concise replies."));
    }

    @Test
    void unknownSenderAddsNoContactSection() throws Exception {
        List<List<ChatMessage>> captured = new ArrayList<>();
        LLMClient llm = messages -> {
            captured.add(messages);
            return "Draft";
        };

        StudentAgent agent = new StudentAgent(
                llm, category -> "", new PromptRepository(), knowledgeBase());
        agent.handle(new EmailMessage("stranger@example.com", "me@example.com",
                "Question", "Hello", "t1"), List.of());

        assertFalse(systemPrompt(captured).contains("## Contact knowledge"));
    }
}

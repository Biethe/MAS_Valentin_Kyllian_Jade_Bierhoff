package com.assistant.mail.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.llm.ScriptedModels;
import com.assistant.mail.mailbox.InMemoryMailbox;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.workflow.Turn;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeTest {
    @TempDir Path dir;

    @BeforeEach void files() throws IOException {
        Files.createDirectories(dir.resolve("teacher"));
        Files.createDirectories(dir.resolve("student"));
        Files.writeString(dir.resolve("teacher/t.txt"),
                "Les rendez-vous se prennent par mail, salle B204.\n\nLe jury de soutenance siège en juin.");
        Files.writeString(dir.resolve("student/s.txt"), "Les inscriptions aux examens ferment le 15 janvier.");
        Files.writeString(dir.resolve("recipients.json"), """
                {"ada@u.test": {"email": "ada@u.test", "name": "Ada Lovelace", "role": "Professor",
                                "organization": "Université de Valmont", "notes": "Préfère le vouvoiement."}}
                """);
    }

    @Test void searchStaysInsideTheProfileAndRanksByWords() {
        ProfileRag rag = new ProfileRag(dir, null);
        List<String> teacher = rag.search("teacher", "rendez-vous salle", 3);
        assertTrue(teacher.get(0).contains("B204"), teacher.toString());
        assertTrue(teacher.stream().noneMatch(c -> c.contains("inscriptions")));
        assertTrue(rag.search(null, "inscriptions examens", 1).get(0).startsWith("[student/s.txt]"));
        assertTrue(rag.search("teacher", "zzz inconnu", 3).isEmpty());
        assertEquals(List.of("student", "teacher"), rag.profiles());
    }

    @Test void embeddingsRankWhenAvailableAndWordsTakeOverWhenTheyFail() {
        // a fake embedder where the text mentioning "jury" points the same way as the query
        Embedder fake = texts -> texts.stream()
                .map(t -> t.toLowerCase().contains("jury") ? new double[] {1, 0} : new double[] {0, 1})
                .toArray(double[][]::new);
        assertTrue(new ProfileRag(dir, fake).search("teacher", "quand siège le jury", 1).get(0).contains("jury"));
        Embedder broken = texts -> { throw new IllegalStateException("down"); };
        assertTrue(new ProfileRag(dir, broken).search("teacher", "rendez-vous salle", 1).get(0).contains("B204"));
    }

    @Test void contactCardsAreLooked_upCaseInsensitively() {
        ContactBook book = new ContactBook(dir.resolve("recipients.json"));
        assertEquals("Ada Lovelace", book.lookup("ADA@u.test").name());
        assertTrue(book.lookup("ada@u.test").describe().contains("vouvoiement"));
        assertNull(book.lookup("nobody@x.y"));
        assertEquals(0, new ContactBook(dir.resolve("missing.json")).size());
    }

    @Test void knowledgeIsTheCardThenPassagesOfTheRecipientProfile() {
        ProfileKnowledge k = ProfileKnowledge.open(dir, null, 2);
        List<String> out = k.knowledgeFor("ada@u.test", "", "rendez-vous salle");
        assertTrue(out.get(0).startsWith("Contact ada@u.test"), out.toString());
        assertTrue(out.get(1).startsWith("[teacher/"), out.toString()); // role came from the card
        assertEquals("student", ProfileKnowledge.profileOf("Étudiant"));
        assertEquals("teacher", ProfileKnowledge.profileOf("professor"));
        assertNull(ProfileKnowledge.profileOf("friend"));
    }

    @Test void theWriterReceivesTheKnowledgeInItsBrief() {
        ScriptedModels base = new ScriptedModels();
        List<String> seen = new ArrayList<>();
        DrafterAgentClient spy = (system, user) -> {
            seen.add(user);
            return base.complete(system, user);
        };
        Assistant a = new Assistant(InMemoryMailbox.demo(), base, spy, base, "knowledge", () -> { })
                .withKnowledge(ProfileKnowledge.open(dir, null, 2));
        Turn.Outcome o = a.ask("Réponds à Ada");
        assertEquals(1, o.drafts.size());
        assertTrue(seen.stream().anyMatch(u -> u.contains("knowledge: Contact ada@u.test: Ada Lovelace")), seen.toString());
    }

    @Test void aBrokenKnowledgeSourceNeverBlocksTheTask() {
        ScriptedModels base = new ScriptedModels();
        Assistant a = new Assistant(InMemoryMailbox.demo(), base, base, base, "broken", () -> { })
                .withKnowledge((recipient, role, query) -> { throw new IllegalStateException("index down"); });
        assertEquals(1, a.ask("Réponds à Ada").drafts.size());
    }
}

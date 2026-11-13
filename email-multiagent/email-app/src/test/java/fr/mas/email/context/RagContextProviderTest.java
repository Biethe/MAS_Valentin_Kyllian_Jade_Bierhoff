package fr.mas.email.context;

import fr.mas.email.model.EmailCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagContextProviderTest {

    @TempDir
    Path tempDir;

    /** Tiny deterministic "embedding": one dimension per topic keyword. */
    private static float[] topicVector(String text) {
        String value = text.toLowerCase();
        return new float[]{
                value.contains("deadline") ? 1f : 0f,
                value.contains("expense") ? 1f : 0f,
                value.contains("course") ? 1f : 0f
        };
    }

    private Path writeContext(String category, String fileName, String content) throws Exception {
        Path directory = tempDir.resolve(category);
        Files.createDirectories(directory);
        Path file = directory.resolve(fileName);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void retrievesOnlyTheMostRelevantChunk() throws Exception {
        writeContext("student", "expenses.txt", "The expense report must include receipts.");
        writeContext("student", "deadlines.txt", "The assignment deadline is Friday.");

        RagContextProvider rag = new RagContextProvider(tempDir, RagContextProviderTest::topicVector, 1);
        String context = rag.contextFor(EmailCategory.STUDENT, "When is the assignment deadline?");

        assertTrue(context.contains("deadline"));
        assertFalse(context.contains("expense"));
    }

    @Test
    void fallsBackToTheWholeCategoryWhenEmbeddingsFail() throws Exception {
        writeContext("student", "expenses.txt", "The expense report must include receipts.");
        writeContext("student", "deadlines.txt", "The assignment deadline is Friday.");

        EmbeddingClient broken = text -> {
            throw new IllegalStateException("model unavailable");
        };
        RagContextProvider rag = new RagContextProvider(tempDir, broken, 1);
        String context = rag.contextFor(EmailCategory.STUDENT, "deadline");

        assertTrue(context.contains("deadline"));
        assertTrue(context.contains("expense"));
    }

    @Test
    void splitsLongDocumentsIntoSeveralChunks() {
        String text = """
                First line of the course description for students.
                Second line continues the same course description.
                Third line adds more detail about the course.
                Fourth line still belongs to the first chunk.
                A short bullet about deadlines.
                Another short bullet about exams.
                """;

        List<String> chunks = RagContextProvider.splitIntoChunks(text);

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 220));
        assertEquals("First line of the course description for students. "
                        + "Second line continues the same course description. "
                        + "Third line adds more detail about the course. "
                        + "Fourth line still belongs to the first chunk.",
                chunks.get(0));
    }
}

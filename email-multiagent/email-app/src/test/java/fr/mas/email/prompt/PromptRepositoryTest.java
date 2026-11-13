package fr.mas.email.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptRepositoryTest {

    @Test
    void switchesLanguageForSubsequentPrompts() {
        PromptRepository repository = new PromptRepository("en");

        assertTrue(repository.load("student").contains("student emails"));

        repository.setLanguage("fr");
        assertEquals("fr", repository.getLanguage());
        assertTrue(repository.load("student").contains("étudiants"));

        repository.setLanguage("en");
        assertTrue(repository.load("student").contains("student emails"));
    }
}

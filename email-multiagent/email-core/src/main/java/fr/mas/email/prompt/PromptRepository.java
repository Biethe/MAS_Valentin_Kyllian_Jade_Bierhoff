package fr.mas.email.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads system prompts from the classpath ({@code prompts/*.md}). */
public final class PromptRepository {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile String language;

    public PromptRepository() {
        this("en");
    }

    public PromptRepository(String language) {
        this.language = normalize(language);
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Switches the language used by subsequent prompts. Cached prompts are
     * keyed by language, so switching back is free.
     */
    public void setLanguage(String language) {
        this.language = normalize(language);
    }

    private static String normalize(String language) {
        return language == null || language.isBlank()
                ? "en"
                : language.trim().toLowerCase(Locale.ROOT);
    }

    public String load(String promptName) {
        String current = language;
        String key = current + "/" + promptName;
        return cache.computeIfAbsent(key, ignored -> read(promptName, current));
    }

    private String read(String promptName, String current) {
        if (!"en".equals(current)) {
            String localized = readResource(promptName + "_" + current);
            if (localized != null) {
                return localized;
            }
        }
        String fallback = readResource(promptName);
        return fallback == null ? "" : fallback;
    }

    private String readResource(String name) {
        String resource = "/prompts/" + name + ".md";
        try (InputStream in = PromptRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}

package fr.mas.email.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Charge les prompts systeme externalises.
 *
 * <p>Ordre de resolution : repertoire externe configure ({@code prompts.dir}),
 * puis ressources du classpath ({@code /prompts/<id>.md}). L'utilisateur peut
 * donc modifier les instructions d'un agent sans recompiler le programme.</p>
 */
public final class PromptRepository {

    private final Path externalDirectory;

    public PromptRepository(Path externalDirectory) {
        this.externalDirectory = externalDirectory;
    }

    public String load(String agentId) {
        if (externalDirectory != null && Files.isDirectory(externalDirectory)) {
            Path file = externalDirectory.resolve(agentId + ".md");
            if (Files.isRegularFile(file)) {
                try {
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("Lecture du prompt impossible : " + file, e);
                }
            }
        }
        return loadFromClasspath("/prompts/" + agentId + ".md");
    }

    private static String loadFromClasspath(String resource) {
        try (InputStream in = PromptRepository.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Prompt introuvable : " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du prompt impossible : " + resource, e);
        }
    }
}

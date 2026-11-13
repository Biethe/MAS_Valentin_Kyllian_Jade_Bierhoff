package fr.mas.email.context;

import fr.mas.email.model.EmailMessage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Contexte charge depuis {@code <baseDir>/<agentId>/}.
 *
 * <p>Les fichiers texte ou markdown places dans ce repertoire sont concatenes
 * et transmis au LLM. Le repertoire est reutilisable et modifiable sans
 * recompilation.</p>
 */
public final class FileContextProvider implements ContextProvider {

    private static final List<String> EXTENSIONS = List.of(".txt", ".md", ".markdown");

    private final Path agentDirectory;
    private final int maxChars;

    public FileContextProvider(Path baseDirectory, String agentId, int maxChars) {
        this.agentDirectory = baseDirectory.resolve(agentId);
        this.maxChars = maxChars;
    }

    @Override
    public String provideContext(EmailMessage email) {
        if (!Files.isDirectory(agentDirectory)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (Stream<Path> files = Files.walk(agentDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(FileContextProvider::isTextFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> appendFile(builder, path));
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du contexte impossible : " + agentDirectory, e);
        }
        return builder.toString();
    }

    private void appendFile(StringBuilder builder, Path path) {
        if (builder.length() >= maxChars) {
            return;
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }
            builder.append("### ").append(path.getFileName()).append("\n")
                    .append(content.strip()).append("\n\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Lecture du fichier de contexte impossible : " + path, e);
        }
    }

    private static boolean isTextFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}

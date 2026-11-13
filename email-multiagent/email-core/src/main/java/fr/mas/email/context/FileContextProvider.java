package fr.mas.email.context;

import fr.mas.email.model.EmailCategory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Loads the context of a category from the files of a directory. The
 * directory is resolved from the application working directory, so the
 * context can be edited without recompiling.
 */
public final class FileContextProvider implements ContextProvider {

    private final Path baseDirectory;

    public FileContextProvider(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public String contextFor(EmailCategory category) {
        Path directory = baseDirectory.resolve(category.name().toLowerCase());
        if (!Files.isDirectory(directory)) {
            return "";
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .sorted()
                    .map(this::read)
                    .collect(Collectors.joining("\n\n"));
        } catch (IOException e) {
            return "";
        }
    }

    private String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}

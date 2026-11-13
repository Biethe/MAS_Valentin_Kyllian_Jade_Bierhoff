package fr.mas.email.context;

import fr.mas.email.config.AppConfig;

import java.time.Duration;

/** Builds the embedding client selected by configuration. */
public final class EmbeddingClientFactory {

    private EmbeddingClientFactory() {
    }

    public static EmbeddingClient create(AppConfig config) {
        String client = config.get("llm.client", "mock").trim().toLowerCase();
        if ("ollama".equals(client)) {
            return new OllamaEmbeddingClient(
                    config.get("llm.endpoint", "http://localhost:11434"),
                    config.get("rag.embedding.model", "all-minilm"),
                    Duration.ofSeconds(config.getInt("rag.embedding.timeout.seconds", 60)));
        }
        return new MockEmbeddingClient();
    }
}

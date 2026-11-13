package fr.mas.email.llm;

import fr.mas.email.config.AppConfig;

import java.time.Duration;

/**
 * Builds the {@link LLMClient} selected by configuration. The mock client is
 * the default so the project runs without a model; set {@code llm.client=ollama}
 * to use a local Ollama server.
 */
public final class LLMClientFactory {

    private LLMClientFactory() {
    }

    public static LLMClient create(AppConfig config) {
        String client = config.get("llm.client", "mock").trim().toLowerCase();
        if ("ollama".equals(client)) {
            return new OllamaClient(
                    config.get("llm.endpoint", "http://localhost:11434"),
                    config.get("llm.model", "llama3.2:1b"),
                    Duration.ofSeconds(config.getInt("llm.timeout.seconds", 120)));
        }
        return new MockLLMClient();
    }
}

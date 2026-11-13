package fr.mas.email.llm;

import fr.mas.email.config.AppConfig;

/**
 * Fabrique de {@link LLMClient} (pattern <em>Factory</em>).
 *
 * <p>Le type de client depend uniquement de la configuration : changer de
 * moteur local ne modifie pas le code des agents.</p>
 */
public final class LLMClientFactory {

    private LLMClientFactory() {
    }

    public static LLMClient create(AppConfig config) {
        if ("mock".equalsIgnoreCase(config.llmProvider())) {
            return new MockLLMClient();
        }
        return new OpenAICompatibleClient(
                config.llmBaseUrl(),
                config.llmApiKey(),
                config.llmModel(),
                config.llmTemperature(),
                config.llmMaxTokens(),
                config.llmTimeoutSeconds());
    }
}

package fr.mas.email.llm;

import java.util.List;

/**
 * Abstraction du moteur d'inference.
 *
 * <p>Permet de remplacer le LLM (Ollama, llama.cpp, OpenRouter, mock...) sans
 * toucher au reste du framework : c'est le point d'application du pattern
 * <em>Adapter</em>.</p>
 */
public interface LLMClient {

    String complete(String systemPrompt, List<ChatMessage> messages);

    default String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(ChatMessage.user(userPrompt)));
    }

    String modelName();
}

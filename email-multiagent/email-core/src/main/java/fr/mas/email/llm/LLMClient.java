package fr.mas.email.llm;

import java.util.List;

/**
 * Abstraction over a local language model. Implementations must not expose a
 * dependency on a specific model or inference engine to the rest of the code.
 */
public interface LLMClient {

    String complete(List<ChatMessage> messages) throws LLMException;
}

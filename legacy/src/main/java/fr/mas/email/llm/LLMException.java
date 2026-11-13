package fr.mas.email.llm;

/**
 * Erreur generique de la couche LLM.
 */
public class LLMException extends RuntimeException {

    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}

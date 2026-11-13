package fr.mas.email.llm;

/** Raised when the LLM cannot produce a response. */
public class LLMException extends RuntimeException {

    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }
}

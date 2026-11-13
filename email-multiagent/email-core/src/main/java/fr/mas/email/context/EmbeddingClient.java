package fr.mas.email.context;

/** Turns text into a vector. */
public interface EmbeddingClient {

    float[] embed(String text) throws Exception;
}

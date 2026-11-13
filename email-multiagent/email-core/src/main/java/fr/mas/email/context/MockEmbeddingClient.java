package fr.mas.email.context;

import java.util.Random;

/**
 * Deterministic embedding used when no local model is available. It is not
 * semantically meaningful, but it keeps the RAG pipeline testable offline.
 */
public final class MockEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSIONS = 64;

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        Random random = new Random(text == null ? 0 : text.hashCode());
        for (int i = 0; i < DIMENSIONS; i++) {
            vector[i] = random.nextFloat() - 0.5f;
        }
        return vector;
    }
}

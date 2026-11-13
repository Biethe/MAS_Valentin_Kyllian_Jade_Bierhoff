package com.assistant.mail.context;

import java.util.List;

/** Turns texts into vectors. Throws when the model is unavailable: the caller falls back to word overlap. */
public interface Embedder {
    double[][] embed(List<String> texts);
}

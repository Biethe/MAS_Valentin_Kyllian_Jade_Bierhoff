package com.assistant.mail.context;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ollama /api/embed (default model all-minilm:22m, small and fast). */
public final class OllamaEmbedder implements Embedder {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaEmbedder(String baseUrl, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
    }

    @Override
    public double[][] embed(List<String> texts) {
        ObjectNode body = JSON.createObjectNode().put("model", model);
        var input = body.putArray("input");
        texts.forEach(input::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/embed"))
                .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("embed " + response.statusCode());
            JsonNode vectors = JSON.readTree(response.body()).path("embeddings");
            if (!vectors.isArray() || vectors.size() != texts.size()) throw new IllegalStateException("bad embed reply");
            double[][] out = new double[texts.size()][];
            for (int i = 0; i < out.length; i++) {
                JsonNode v = vectors.get(i);
                out[i] = new double[v.size()];
                for (int j = 0; j < v.size(); j++) out[i][j] = v.get(j).asDouble();
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("embedding model unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}

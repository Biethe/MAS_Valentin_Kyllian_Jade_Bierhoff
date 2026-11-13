package fr.mas.email.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Ollama embedding client backed by {@code /api/embeddings}. */
public final class OllamaEmbeddingClient implements EmbeddingClient {

    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaEmbeddingClient(String endpoint, String model, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint").replaceAll("/+$", "");
        this.model = Objects.requireNonNull(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public float[] embed(String text) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("prompt", text);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/embeddings"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama embeddings returned HTTP " + response.statusCode());
        }
        JsonNode node = mapper.readTree(response.body());
        JsonNode array = node.path("embedding");
        if (!array.isArray()) {
            throw new IllegalStateException("Ollama embeddings returned no embedding");
        }
        float[] vector = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            vector[i] = (float) array.get(i).asDouble();
        }
        return vector;
    }
}

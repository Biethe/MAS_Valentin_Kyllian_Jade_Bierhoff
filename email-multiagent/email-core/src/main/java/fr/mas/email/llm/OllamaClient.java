package fr.mas.email.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * {@link LLMClient} backed by a local Ollama server. It talks to the
 * {@code /api/chat} endpoint and keeps the rest of the application independent
 * of the inference engine.
 */
public final class OllamaClient implements LLMClient {

    private final String endpoint;
    private final String model;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaClient(String endpoint, String model, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint").replaceAll("/+$", "");
        this.model = Objects.requireNonNull(model, "model");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.mapper = new ObjectMapper();
    }

    @Override
    public String complete(List<ChatMessage> messages) throws LLMException {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("stream", false);

            ArrayNode array = root.putArray("messages");
            for (ChatMessage message : messages) {
                ObjectNode node = array.addObject();
                node.put("role", message.getRole());
                node.put("content", message.getContent());
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new LLMException("Ollama returned HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode node = mapper.readTree(response.body());
            String content = node.path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new LLMException("Ollama returned an empty response");
            }
            return content.trim();
        } catch (LLMException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Ollama request interrupted", e);
        } catch (Exception e) {
            throw new LLMException("Ollama request failed: " + e.getMessage(), e);
        }
    }
}

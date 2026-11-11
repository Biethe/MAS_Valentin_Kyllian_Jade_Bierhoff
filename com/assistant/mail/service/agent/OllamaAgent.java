package com.assistant.mail.service.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.assistant.mail.service.Progress;

/** One local model for the planner, the drafter, and the summarizer. Temperature 0, seed 42. */
public final class OllamaAgent implements PlannerAgentClient, DrafterAgentClient, SummarizerAgentClient {
    private final String model = System.getenv().getOrDefault("AGENT_MODEL", "qwen3.5:4b");
    private final String embedModel = System.getenv().getOrDefault("EMBED_MODEL", "all-minilm:22m");
    private final String base = System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public String complete(String system, String user) {
        Progress.note("Thinking with " + model);
        ObjectNode body = json.createObjectNode().put("model", model).put("stream", false);
        body.putArray("messages")
                .add(json.createObjectNode().put("role", "system").put("content", system == null ? "" : system))
                .add(json.createObjectNode().put("role", "user").put("content", user == null ? "" : user));
        body.putObject("options").put("temperature", 0).put("seed", 42);
        body.put("think", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("ollama " + response.statusCode());
            return json.readTree(response.body()).path("message").path("content").asString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public float[] embed(String text) {
        Progress.note("Embedding with " + embedModel);
        ObjectNode body = json.createObjectNode().put("model", embedModel).put("input", text == null ? "" : text);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/embed"))
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("ollama embed " + response.statusCode());
            JsonNode tree = json.readTree(response.body());
            JsonNode vectors = tree.path("embeddings");
            JsonNode vector = vectors.isArray() && !vectors.isEmpty() ? vectors.get(0) : tree.path("embedding");
            float[] out = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) out[i] = (float) vector.get(i).asDouble();
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

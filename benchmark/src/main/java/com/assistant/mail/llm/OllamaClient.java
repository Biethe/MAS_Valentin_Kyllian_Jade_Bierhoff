package com.assistant.mail.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Ollama's HTTP API: /api/chat (non-streaming, temperature 0, seed 42) and model digests from /api/tags. */
public class OllamaClient {

    static final JsonMapper JSON = JsonMapper.builder().build();
    final String base = System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434");
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public String chat(String model, String system, String user) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false);
        body.putArray("messages")
                .add(JSON.createObjectNode().put("role", "system").put("content", system))
                .add(JSON.createObjectNode().put("role", "user").put("content", user));
        body.putObject("options").put("temperature", 0).put("seed", 42);
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/chat")).timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("ollama " + res.statusCode() + ": " + res.body());
        return JSON.readTree(res.body()).path("message").path("content").asString();
    }

    /** The installed model's digest, or null if it isn't pulled. Throws if Ollama is unreachable. */
    public String digest(String model) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/tags")).timeout(Duration.ofSeconds(10)).build();
        for (JsonNode m : JSON.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).path("models"))
            if (m.path("name").asString().equals(model)) return m.path("digest").asString();
        return null;
    }
}

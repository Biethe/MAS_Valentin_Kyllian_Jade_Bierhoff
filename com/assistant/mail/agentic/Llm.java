package com.assistant.mail.agentic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The only thing an agent knows about its language model: chat messages in, one assistant message out.
 * Swapping the model or the inference engine means another implementation of this interface (Adapter).
 */
public interface Llm {
    JsonMapper JSON = JsonMapper.builder().build();

    /** @param tools function schemas, or null; @param jsonOnly force a JSON object answer */
    JsonNode chat(ArrayNode messages, ArrayNode tools, boolean jsonOnly);

    /** Sentence embedding, for meaning-based search. Null when the engine has no embedding model. */
    default double[] embed(String text) {
        return null;
    }

    static Llm fromEnv() {
        return new Ollama(System.getenv().getOrDefault("AGENT_MODEL", "llama3.1:8b"),
                System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434"));
    }

    static ObjectNode msg(String role, String content) {
        return JSON.createObjectNode().put("role", role).put("content", content == null ? "" : content);
    }

    /** Ollama's /api/chat with native tool calling. Temperature 0 and a fixed seed keep runs repeatable. */
    final class Ollama implements Llm {
        private final String model, base;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        Ollama(String model, String base) {
            this.model = model;
            this.base = base;
        }

        @Override
        public double[] embed(String text) {
            ObjectNode body = JSON.createObjectNode().put("model", System.getenv().getOrDefault("EMBED_MODEL", "all-minilm:22m"))
                    .put("input", text).put("keep_alive", "10m");
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/embed")).timeout(Duration.ofMinutes(1))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return null;
                JsonNode v = JSON.readTree(r.body()).path("embeddings").path(0);
                double[] out = new double[v.size()];
                for (int i = 0; i < out.length; i++) out[i] = v.get(i).asDouble();
                return out.length == 0 ? null : out;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public JsonNode chat(ArrayNode messages, ArrayNode tools, boolean jsonOnly) {
            ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false).put("keep_alive", "10m");
            body.set("messages", messages);
            if (tools != null) body.set("tools", tools);
            if (jsonOnly) body.put("format", "json");
            body.putObject("options").put("temperature", 0).put("seed", 42).put("num_predict", 1024);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/chat")).timeout(Duration.ofMinutes(4))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            try {
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IllegalStateException("ollama " + r.statusCode() + ": " + r.body());
                JsonNode m = JSON.readTree(r.body()).path("message");
                if (System.getenv("MAIL_DEBUG") != null) System.err.println("--- " + m);
                return m;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("ollama unreachable at " + base, e);
            }
        }
    }
}

package com.assistant.mail.llm;

import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One local model behind the three agent ports. Ollama /api/chat, non-streaming, temperature 0. */
public final class OllamaChat implements PlannerAgentClient, DrafterAgentClient, SummarizerAgentClient {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaChat(String baseUrl, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
    }

    public String model() { return model; }

    @Override
    public String complete(String system, String user) {
        ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false).put("think", false);
        body.putArray("messages")
                .add(JSON.createObjectNode().put("role", "system").put("content", system == null ? "" : system))
                .add(JSON.createObjectNode().put("role", "user").put("content", user == null ? "" : user));
        body.putObject("options").put("temperature", 0).put("seed", 42);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Ollama " + response.statusCode() + ": " + response.body());
            }
            String content = stripThinking(JSON.readTree(response.body()).path("message").path("content").asString(""));
            if (System.getenv("ASSISTANT_DEBUG") != null) {
                System.err.println("--- model call ---\n[system] " + firstLine(system) + "\n[user] " + user
                        + "\n[reply] " + content + "\n");
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Ollama unreachable at " + baseUrl + " (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Reasoning models may wrap their thoughts in think tags; the workflows only want the answer. */
    static String stripThinking(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").strip();
    }

    /** Models installed on the server (empty when it cannot be listed). */
    public static java.util.List<String> installed(String baseUrl) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(base + "/api/tags")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.ofString());
            for (var m : JSON.readTree(r.body()).path("models")) names.add(m.path("name").asString(""));
        } catch (IOException | RuntimeException e) {
            // unreachable server: reported by the caller
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return names;
    }

    private static final java.util.List<String> PREFERRED = java.util.List.of(
            "qwen3", "llama3.2", "qwen2.5", "gemma", "mistral", "llama3.1", "llama3");

    /**
     * The model to use: the requested one when installed, else the best installed instruction-following
     * model (a tiny or non-instruction model ignores the prompt format), else null.
     */
    public static String pick(String requested, java.util.List<String> installed) {
        if (installed.contains(requested)) return requested;
        for (String name : installed) if (name.equals(requested + ":latest")) return name;
        for (String family : PREFERRED) {
            for (String name : installed) {
                if (name.toLowerCase(java.util.Locale.ROOT).contains(family) && !name.contains("0.6b")
                        && !name.contains(":1b")) return name;
            }
        }
        return null;
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    /** True when the server answers. A busy server (loading a model) is given time: 3 tries of 20 s. */
    public boolean reachable() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                        .timeout(Duration.ofSeconds(20)).build();
                if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return true;
            } catch (IOException e) {
                // try again
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}

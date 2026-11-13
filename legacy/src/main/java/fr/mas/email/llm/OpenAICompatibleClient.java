package fr.mas.email.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Client pour toute API compatible OpenAI {@code /chat/completions}.
 *
 * <p>Compatible Ollama, llama.cpp server, LM Studio, vLLM et OpenRouter.
 * Le baseUrl et le modele sont fournis par la configuration.</p>
 */
public final class OpenAICompatibleClient implements LLMClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final Duration requestTimeout;

    public OpenAICompatibleClient(String baseUrl, String apiKey, String model,
                                  double temperature, int maxTokens, int timeoutSeconds) {
        String normalisedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = normalisedBase + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)))
                .build();
    }

    @Override
    public String complete(String systemPrompt, List<ChatMessage> messages) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        payload.put("stream", false);

        ArrayNode chatMessages = payload.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            chatMessages.add(messageNode("system", systemPrompt));
        }
        for (ChatMessage message : messages) {
            chatMessages.add(messageNode(message.role(), message.content()));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

        if (!apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new LLMException("Erreur LLM HTTP " + response.statusCode() + " : " + response.body());
            }
            return extractContent(response.body());
        } catch (IOException e) {
            throw new LLMException("Echec de communication avec le LLM (" + endpoint + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("Inference interrompue", e);
        }
    }

    private static ObjectNode messageNode(String role, String content) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static String extractContent(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new LLMException("Reponse LLM sans contenu exploitable : " + body);
            }
            return content.asText();
        } catch (IOException e) {
            throw new LLMException("Reponse LLM illisible : " + body, e);
        }
    }

    @Override
    public String modelName() {
        return model;
    }
}

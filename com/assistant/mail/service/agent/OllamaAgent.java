package com.assistant.mail.service.agent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.service.Progress;

/** Writer and summarizer use {@code AGENT_MODEL}. Planner, critic, and judge each have their own model. */
public final class OllamaAgent implements PlannerAgentClient, DrafterAgentClient, SummarizerAgentClient {
    private final String model = writerModel();
    private final String plannerModel = plannerModel();
    private final String criticModel = criticModel();
    private final String judgeModel = judgeModel();
    private final String embedModel = System.getenv().getOrDefault("EMBED_MODEL", "all-minilm:22m");
    private final String base = System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();

    public static String writerModel() {
        return System.getenv().getOrDefault("AGENT_MODEL", "qwen3.5:4b");
    }

    public static String plannerModel() {
        return System.getenv().getOrDefault("PLANNER_MODEL", "qwen3.5:9b-q4_K_M");
    }

    public static String criticModel() {
        return System.getenv().getOrDefault("CRITIC_MODEL", "llama3.1:8b");
    }

    public static String judgeModel() {
        return System.getenv().getOrDefault("JUDGE_MODEL", "tev1");
    }

    @Override
    public String complete(String system, String user) {
        String chosen = choose(system, model, plannerModel, criticModel, judgeModel);
        if (chosen.equals(judgeModel) && same(system, PlannerSystemPrompt.JUDGE, DrafterSystemPrompt.JUDGE, SummarizerSystemPrompt.JUDGE)) {
            return decide(system, user);
        }
        Progress.note("Thinking with " + chosen);
        ObjectNode body = json.createObjectNode().put("model", chosen).put("stream", false);
        body.putArray("messages")
                .add(json.createObjectNode().put("role", "system").put("content", system == null ? "" : system))
                .add(json.createObjectNode().put("role", "user").put("content", user == null ? "" : user));
        body.putObject("options").put("temperature", 0).put("seed", 42).put("num_ctx", 8192);
        body.put("think", false);
        body.put("keep_alive", "10m");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/chat"))
                .timeout(Duration.ofMinutes(6))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return chat(request);
    }

    private String chat(HttpRequest request) {
        try {
            return readChat(request);
        } catch (IOException first) {
            try {
                Thread.sleep(1500);
                return readChat(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private String readChat(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("ollama " + response.statusCode() + " " + response.body());
            return json.readTree(response.body()).path("message").path("content").asString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
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

    /** Tev1 answers a choice. It has no chat API. The workflows still read {@code winner:}. */
    private String decide(String system, String user) {
        Progress.note("Deciding with " + judgeModel);
        String other = system.equals(DrafterSystemPrompt.JUDGE) ? "writer"
                : system.equals(SummarizerSystemPrompt.JUDGE) ? "summarizer" : "planner";
        String state = user == null ? "" : user;
        if (state.length() > 6000) state = state.substring(0, 6000);
        ObjectNode body = json.createObjectNode().put("model", judgeModel).put("state", state);
        ObjectNode winner = body.putObject("questions").putObject("winner");
        winner.put("type", "choice").put("instructions", "Who is right?");
        winner.putObject("criteria").put("critic", "The critic found a real mistake.")
                .put(other, "The other side already satisfies the brief.");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/systemone"))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            return winner(request, other);
        } catch (RuntimeException first) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "winner:\n" + other;
            }
            try {
                return winner(request, other);
            } catch (RuntimeException ignored) {
                Progress.note("judge skipped");
                return "winner:\n" + other;
            }
        }
    }

    private String winner(HttpRequest request, String other) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("tev1 " + response.statusCode() + " " + response.body());
            String choice = json.readTree(response.body()).path("answers").path("winner").path("choice").asString("");
            if (!choice.equals("critic") && !choice.equals(other)) choice = other;
            return "winner:\n" + choice;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean same(String system, String... prompts) {
        if (system == null) return false;
        for (String prompt : prompts) if (system.equals(prompt)) return true;
        return false;
    }

    /** Critic prompts use the critic model. Judge prompts use the decision model. Other planner prompts use the planner. */
    static String choose(String system, String writer, String planner, String critic, String judge) {
        if (same(system, PlannerSystemPrompt.JUDGE, DrafterSystemPrompt.JUDGE, SummarizerSystemPrompt.JUDGE)) return judge;
        if (same(system, PlannerSystemPrompt.CRITIC, DrafterSystemPrompt.CRITIC, SummarizerSystemPrompt.CRITIC)) return critic;
        if (same(system, PlannerSystemPrompt.SPLIT, PlannerSystemPrompt.ROUTE, PlannerSystemPrompt.ANSWER,
                PlannerSystemPrompt.TOOLS, PlannerSystemPrompt.ASK_USER, PlannerSystemPrompt.LOCATE,
                PlannerSystemPrompt.READ, PlannerSystemPrompt.BRIEF, PlannerSystemPrompt.ARGUMENT,
                PlannerSystemPrompt.LESSON, PlannerSystemPrompt.CHECKLIST)) {
            return planner;
        }
        return writer;
    }

    public static void main(String[] args) {
        assert "p".equals(choose(PlannerSystemPrompt.ROUTE, "w", "p", "c", "j"));
        assert "c".equals(choose(PlannerSystemPrompt.CRITIC, "w", "p", "c", "j"));
        assert "c".equals(choose(DrafterSystemPrompt.CRITIC, "w", "p", "c", "j"));
        assert "c".equals(choose(SummarizerSystemPrompt.CRITIC, "w", "p", "c", "j"));
        assert "j".equals(choose(PlannerSystemPrompt.JUDGE, "w", "p", "c", "j"));
        assert "j".equals(choose(DrafterSystemPrompt.JUDGE, "w", "p", "c", "j"));
        assert "j".equals(choose(SummarizerSystemPrompt.JUDGE, "w", "p", "c", "j"));
        assert "w".equals(choose(DrafterSystemPrompt.TEXT, "w", "p", "c", "j"));
        assert "w".equals(choose(SummarizerSystemPrompt.TEXT, "w", "p", "c", "j"));
        assert "p".equals(choose(PlannerSystemPrompt.LESSON, "w", "p", "c", "j"));
        System.out.println("OllamaAgent ok");
    }
}

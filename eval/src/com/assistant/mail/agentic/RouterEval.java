package com.assistant.mail.agentic;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Routing accuracy of the agentic {@link Router.ByLlm} on a labelled e-mail set (same files as the category routers).
 * It lives in this package only to reach the package-private constructor; nothing under com/ is modified.
 *
 * The router receives a request, not a raw mail, so each mail is presented as the owner's request
 * "Reply to this mail from ...". The labels STUDENT/TEACHER/ADMINISTRATION/RESEARCH/GENERIC are matched to the
 * specialists student/teacher/administration/research/generic. SPAM and NO_REPLY rows are not in these sets.
 *
 * Usage: RouterEval specialists-dir model dataset.jsonl [out.csv]
 * The model is called like Llm.Ollama does (temperature 0, seed 42, JSON answer) plus think=false, so that a
 * reasoning model answers directly. An empty or unknown answer is counted as NO_ANSWER, not as generic.
 */
public final class RouterEval {
    private record Row(String sender, String subject, String body, String expected) {}

    /** Records what the model returned so an empty answer is not silently scored as "generic". */
    private static final class Probe implements Llm {
        private final String model;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String lastContent = "";

        Probe(String model) { this.model = model; }

        @Override
        public JsonNode chat(ArrayNode messages, ArrayNode tools, boolean jsonOnly) {
            ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false).put("think", false)
                    .put("keep_alive", "10m");
            body.set("messages", messages);
            if (jsonOnly) body.put("format", "json");
            body.putObject("options").put("temperature", 0).put("seed", 42).put("num_predict", 1024);
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(
                        System.getenv().getOrDefault("OLLAMA_HOST", "http://localhost:11434") + "/api/chat"))
                        .timeout(Duration.ofMinutes(4)).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) throw new IllegalStateException("ollama " + r.statusCode());
                JsonNode m = JSON.readTree(r.body()).path("message");
                lastContent = m.path("content").asString("");
                return m;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<Specialist> specialists = Specialist.load(Path.of(args[0]));
        Probe llm = new Probe(args[1]);
        Router router = new Router.ByLlm(llm);
        List<Row> rows = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(args[2]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            JsonNode n = Llm.JSON.readTree(line);
            rows.add(new Row(n.path("sender").asString(""), n.path("subject").asString(""), n.path("body").asString(""),
                    n.path("expected").asString("").toUpperCase(Locale.ROOT)));
        }
        Map<String, int[]> perCategory = new LinkedHashMap<>();
        StringBuilder csv = new StringBuilder("subject,expected,predicted,ok,seconds\n");
        int correct = 0;
        System.out.printf("=== Agentic Router.ByLlm | model %s | %s (%d mails) ===%n", args[1], Path.of(args[2]).getFileName(), rows.size());
        for (Row row : rows) {
            String request = "Reply to this mail from " + row.sender() + ". Subject: " + row.subject() + ". Message: " + row.body();
            long start = System.currentTimeMillis();
            llm.lastContent = "";
            String predicted;
            try {
                Specialist chosen = router.route(request, specialists);
                boolean answered = !llm.lastContent.isBlank() && llm.lastContent.toLowerCase(Locale.ROOT).contains(chosen.name());
                predicted = answered ? chosen.name().toUpperCase(Locale.ROOT) : "NO_ANSWER";
            } catch (RuntimeException e) {
                predicted = "ERROR";
            }
            double seconds = (System.currentTimeMillis() - start) / 1000.0;
            boolean ok = predicted.equals(row.expected());
            if (ok) correct++;
            perCategory.computeIfAbsent(row.expected(), k -> new int[2])[1]++;
            if (ok) perCategory.get(row.expected())[0]++;
            System.out.printf(Locale.ROOT, "  %-34s expected=%-14s predicted=%-14s %s%n",
                    row.subject().length() > 34 ? row.subject().substring(0, 34) : row.subject(), row.expected(), predicted, ok ? "OK" : "MISS");
            csv.append('"').append(row.subject().replace("\"", "'")).append("\",").append(row.expected()).append(',')
                    .append(predicted).append(',').append(ok).append(',').append(String.format(Locale.ROOT, "%.1f", seconds)).append('\n');
        }
        System.out.printf(Locale.ROOT, "Accuracy: %d/%d (%.1f%%)%n", correct, rows.size(), 100.0 * correct / rows.size());
        perCategory.forEach((k, v) -> System.out.printf(Locale.ROOT, "    %-16s %d/%d%n", k, v[0], v[1]));
        if (args.length > 3) Files.writeString(Path.of(args[3]), csv.toString());
    }
}

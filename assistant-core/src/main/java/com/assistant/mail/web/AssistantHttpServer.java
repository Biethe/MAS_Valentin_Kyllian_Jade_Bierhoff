package com.assistant.mail.web;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.OutcomeJson;
import com.assistant.mail.model.output.Draft;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Small HTTP front end: a chat page at / and a JSON API.
 * {@code POST /api/assistant {"text": "..."}}, {@code GET /api/drafts}, {@code GET /api/health}.
 * Listens on localhost only.
 */
public final class AssistantHttpServer {
    private final HttpServer server;
    private final Assistant assistant;

    public AssistantHttpServer(Assistant assistant, int port) throws IOException {
        this.assistant = assistant;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::page);
        server.createContext("/api/health", this::health);
        server.createContext("/api/assistant", this::ask);
        server.createContext("/api/drafts", this::drafts);
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    private void page(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain; charset=utf-8", "not found");
            return;
        }
        try (InputStream in = getClass().getResourceAsStream("/web/assistant.html")) {
            send(ex, 200, "text/html; charset=utf-8", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void health(HttpExchange ex) throws IOException {
        json(ex, 200, Map.of("status", "ok", "assistant", assistant.description()));
    }

    private void ask(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            json(ex, 405, Map.of("error", "POST only"));
            return;
        }
        String text;
        try {
            JsonNode body = OutcomeJson.JSON.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            text = body.path("text").asString("").strip();
        } catch (RuntimeException e) {
            json(ex, 400, Map.of("error", "body must be JSON {\"text\": \"...\"}"));
            return;
        }
        if (text.isEmpty()) {
            json(ex, 400, Map.of("error", "text is empty"));
            return;
        }
        try {
            json(ex, 200, OutcomeJson.of(assistant.ask(text), true));
        } catch (RuntimeException e) {
            json(ex, 502, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void drafts(HttpExchange ex) throws IOException {
        List<Object> out = new ArrayList<>();
        for (Draft d : assistant.savedDrafts()) out.add(OutcomeJson.draft(d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("drafts", out);
        json(ex, 200, body);
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", OutcomeJson.toString(body));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }
}

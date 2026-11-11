package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.ask.QuestionReason;
import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.fact.InformationKind;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.service.Progress;
import com.assistant.mail.service.agent.OllamaAgent;
import com.assistant.mail.service.mcp.MailboxClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** HTTP bridge so the workspace can run one {@link Turn}. Same mailbox switch as {@link MailMain}. */
public final class UiServer {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path MEMORY = Path.of("memory.json");
    private static final int PORT = 8787;

    private UiServer() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "check".equals(args[0])) {
            Turn.Outcome outcome = new Turn.Outcome();
            outcome.questions.add(new Question("Which mail?", QuestionReason.NO_MAIL, List.of()));
            String body = payload(outcome);
            if (!body.contains("Which mail?")) throw new IllegalStateException(body);
            System.out.println("UiServer ok");
            return;
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/status", UiServer::status);
        server.createContext("/api/progress", UiServer::progress);
        server.createContext("/api/reset", UiServer::reset);
        server.createContext("/api/turn", UiServer::turn);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("UI agent on http://127.0.0.1:" + PORT + " mailbox=" + mailbox());
    }

    static String payload(Turn.Outcome outcome) {
        Turn.Outcome turn = outcome == null ? new Turn.Outcome() : outcome;
        ObjectNode root = JSON.createObjectNode();
        root.put("text", TraceFile.text(turn));
        root.put("mailbox", mailbox());
        if (!turn.summaries.isEmpty()) {
            StringBuilder body = new StringBuilder();
            ArrayNode points = root.putArray("points");
            ArrayNode sources = root.putArray("sources");
            for (Summary summary : turn.summaries) {
                if (summary.getBody() != null && !summary.getBody().isBlank()) {
                    if (body.length() > 0) body.append("\n\n");
                    body.append(summary.getBody().strip());
                }
                if (summary.getInformation() != null) {
                    for (Information fact : summary.getInformation()) point(points, fact);
                }
                if (summary.getReferences() != null) {
                    for (String ref : summary.getReferences()) {
                        if (ref != null && !ref.isBlank()) sources.add(ref);
                    }
                }
            }
            root.put("summary", body.toString());
        }
        ArrayNode questions = root.putArray("questions");
        for (Question question : turn.questions) {
            if (question.getText() != null && !question.getText().isBlank()) questions.add(question.getText().strip());
        }
        ArrayNode refused = root.putArray("refused");
        for (String line : turn.refused) if (line != null && !line.isBlank()) refused.add(line.strip());
        ArrayNode chats = root.putArray("chats");
        for (String line : turn.chats) if (line != null && !line.isBlank()) chats.add(line.strip());
        if (!turn.drafts.isEmpty()) root.set("draft", draftNode(turn.drafts.get(0)));
        if (chats.isEmpty() && questions.isEmpty() && refused.isEmpty() && !root.has("draft") && !root.has("summary")) {
            chats.add(root.path("text").asString("No answer."));
        }
        return root.toString();
    }

    private static void point(ArrayNode points, Information fact) {
        String body = fact.getBody() == null ? "" : fact.getBody().strip();
        if (body.isBlank()) return;
        ObjectNode point = points.addObject().put("title", body);
        if (fact.getKind() == InformationKind.SUSPICIOUS_INSTRUCTION) {
            point.put("detail", "Flagged instruction found in a mail");
        }
    }

    private static ObjectNode draftNode(Draft draft) {
        ObjectNode node = JSON.createObjectNode();
        node.put("to", join(draft.getRecipients()));
        node.put("cc", join(draft.getCc()));
        node.put("bcc", join(draft.getBcc()));
        node.put("subject", draft.getSubject() == null ? "" : draft.getSubject());
        node.put("body", draft.getBody() == null ? "" : draft.getBody());
        node.put("tone", draft.getTone() == null ? "" : draft.getTone().name());
        return node;
    }

    private static String join(List<String> values) {
        return values == null ? "" : String.join(", ", values);
    }

    private static String mailbox() {
        return System.getenv().getOrDefault("MAIL_MCP", "university");
    }

    private static void status(HttpExchange exchange) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("mailbox", mailbox());
        root.put("scenario", System.getenv().getOrDefault("SCENARIO", ""));
        root.put("model", System.getenv().getOrDefault("AGENT_MODEL", "qwen3.5:4b"));
        send(exchange, 200, root.toString());
    }

    private static void progress(HttpExchange exchange) throws IOException {
        ArrayNode lines = JSON.createArrayNode();
        for (String line : Progress.lines()) lines.add(line);
        send(exchange, 200, JSON.createObjectNode().set("lines", lines).toString());
    }

    private static void reset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"POST\"}");
            return;
        }
        MemoryFile.save(MEMORY, new LongTermMemory());
        send(exchange, 200, "{\"ok\":true}");
    }

    private static void turn(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"POST\"}");
            return;
        }
        String message;
        String history = "";
        List<AttachmentFile> files = new ArrayList<>();
        try {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            message = body.path("message").asString("").strip();
            history = body.path("history").asString("").strip();
            for (JsonNode file : body.path("files")) {
                String name = file.path("name").asString("file");
                String text = file.path("text").asString("");
                if (text.length() > 20_000) text = text.substring(0, 20_000);
                files.add(new AttachmentFile(name, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (RuntimeException e) {
            send(exchange, 400, error("Unreadable request"));
            return;
        }
        if (message.isBlank()) {
            send(exchange, 400, error("Write a request first"));
            return;
        }
        System.out.println("turn " + message);
        Progress.clear();
        Progress.note("Starting");
        try {
            OllamaAgent model = new OllamaAgent();
            LongTermMemory memory = MemoryFile.load(MEMORY);
            Turn.Outcome outcome;
        String prompt = history.isBlank() ? message : "Conversation so far:\n" + history + "\nCurrent request:\n" + message;
            try (MailboxClient mail = MailboxClient.open()) {
                outcome = new Turn(new Planner(mail, model),
                        new WriterWorkflow(model, mail, memory, null),
                        new SummaryWorkflow(model, memory, null))
                        .run(new Request(prompt, files), memory);
            }
            MemoryFile.save(MEMORY, memory);
            System.out.println("turn done " + TraceFile.text(outcome));
            Progress.note("Finished");
            send(exchange, 200, payload(outcome));
        } catch (RuntimeException e) {
            String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.out.println("turn error " + text);
            Progress.note("Stopped: " + text);
            send(exchange, 500, error(text));
        }
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

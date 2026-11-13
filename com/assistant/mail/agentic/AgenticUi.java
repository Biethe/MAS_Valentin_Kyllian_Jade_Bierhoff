package com.assistant.mail.agentic;

import com.assistant.mail.service.Progress;
import com.assistant.mail.service.mcp.MailboxClient;
import com.assistant.mail.workflow.turn.Turn;
import com.assistant.mail.workflow.turn.UiServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTTP side of the "Agents" mode of the chat page, added to the existing UiServer (same port, localhost only).
 * <ul>
 *   <li>{@code POST /api/agentic/turn}: router → specialist → opponent → coordinator for one request.</li>
 *   <li>{@code GET /api/agents}: the specialists, their prompt, their built-in context and their context files.</li>
 *   <li>{@code POST /api/agents}: create a specialist. {@code PUT /api/agents/{name}}: change description, prompt, context.</li>
 *   <li>{@code GET|PUT|DELETE /api/agents/{name}/files/{file}}: the specialist's context documents.</li>
 * </ul>
 * Writes stay inside the specialists folder and the context folder; names and file names are validated; an agent is
 * never deleted. Specialists and documents are re-read for every request, so an edit applies to the next turn.
 */
public final class AgenticUi {
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{1,30}");
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,60}\\.(txt|md)");
    static final int MAX_BYTES = 100_000;

    private final Path specialists;
    private final Path context;

    AgenticUi(Path specialists, Path context) {
        this.specialists = specialists.toAbsolutePath().normalize();
        this.context = context.toAbsolutePath().normalize();
    }

    /** Adds the routes to the UI server. Folders: SPECIALISTS (default specialists) and CONTEXT_DIR (default context). */
    public static void register(HttpServer server) {
        AgenticUi ui = new AgenticUi(Path.of(System.getenv().getOrDefault("SPECIALISTS", "specialists")),
                Path.of(System.getenv().getOrDefault("CONTEXT_DIR", "context")));
        ui.register(server, true);
    }

    void register(HttpServer server, boolean withTurn) {
        server.createContext("/api/agents", this::agents);
        if (withTurn) server.createContext("/api/agentic/turn", this::turn);
    }

    // ------------------------------------------------------------------ agents and their files

    private void agents(HttpExchange ex) throws IOException {
        try {
            String[] parts = ex.getRequestURI().getPath().replaceFirst("^/api/agents/?", "").split("/");
            String method = ex.getRequestMethod();
            if (parts.length == 1 && parts[0].isEmpty()) {
                if (method.equals("GET")) list(ex);
                else if (method.equals("POST")) create(ex);
                else send(ex, 405, error("GET or POST"));
            } else if (parts.length == 1) {
                if (method.equals("PUT")) update(ex, parts[0]);
                else send(ex, 405, error("PUT"));
            } else if (parts.length == 3 && parts[1].equals("files")) {
                file(ex, method, parts[0], parts[2]);
            } else {
                send(ex, 404, error("Unknown route"));
            }
        } catch (BadRequest e) {
            send(ex, e.status, error(e.getMessage()));
        } catch (RuntimeException e) {
            send(ex, 500, error(String.valueOf(e.getMessage())));
        }
    }

    private void list(HttpExchange ex) throws IOException {
        ObjectNode root = Llm.JSON.createObjectNode();
        root.put("retrieval", System.getenv("CONTEXT_DIR") != null);
        ArrayNode out = root.putArray("agents");
        for (Specialist s : Specialist.load(specialists)) {
            ObjectNode a = out.addObject();
            a.put("name", s.name()).put("description", s.description()).put("prompt", s.prompt()).put("context", s.context());
            ArrayNode files = a.putArray("files");
            Path folder = context.resolve(s.name());
            if (Files.isDirectory(folder)) {
                try (Stream<Path> stream = Files.list(folder)) {
                    for (Path f : stream.filter(p -> FILE.matcher(p.getFileName().toString()).matches()).sorted().toList()) {
                        files.addObject().put("name", f.getFileName().toString()).put("size", Files.size(f));
                    }
                }
            }
        }
        send(ex, 200, root.toString());
    }

    private void create(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String name = body.path("name").asString("").strip();
        if (!NAME.matcher(name).matches()) throw new BadRequest(400, "Name: 2-31 characters, a-z, 0-9, - or _, starting with a letter");
        Path file = specialists.resolve(name + ".md");
        if (Files.exists(file)) throw new BadRequest(409, "An agent named " + name + " already exists");
        write(file, render(body.path("description").asString(""), body.path("prompt").asString(""), ""));
        send(ex, 201, Llm.JSON.createObjectNode().put("name", name).toString());
    }

    private void update(HttpExchange ex, String name) throws IOException {
        if (!NAME.matcher(name).matches()) throw new BadRequest(400, "Bad agent name");
        Path file = specialists.resolve(name + ".md");
        if (!Files.isRegularFile(file)) throw new BadRequest(404, "No agent named " + name);
        JsonNode body = readJson(ex);
        write(file, render(body.path("description").asString(""), body.path("prompt").asString(""),
                body.path("context").asString("")));
        send(ex, 200, Llm.JSON.createObjectNode().put("name", name).toString());
    }

    private void file(HttpExchange ex, String method, String agent, String name) throws IOException {
        if (!NAME.matcher(agent).matches() || !Files.isRegularFile(specialists.resolve(agent + ".md"))) {
            throw new BadRequest(404, "No agent named " + agent);
        }
        if (!FILE.matcher(name).matches() || name.contains("..")) throw new BadRequest(400, "File name: letters, digits, . - _ and .txt or .md");
        Path path = context.resolve(agent).resolve(name).normalize();
        if (!path.startsWith(context)) throw new BadRequest(400, "Bad path");
        switch (method) {
            case "GET" -> {
                if (!Files.isRegularFile(path)) throw new BadRequest(404, "No such file");
                send(ex, 200, Llm.JSON.createObjectNode().put("name", name).put("text", Files.readString(path)).toString());
            }
            case "PUT" -> {
                String text = readJson(ex).path("text").asString("");
                if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new BadRequest(413, "File too large (100 KB maximum)");
                write(path, text);
                send(ex, 200, Llm.JSON.createObjectNode().put("name", name).toString());
            }
            case "DELETE" -> {
                if (!Files.deleteIfExists(path)) throw new BadRequest(404, "No such file");
                send(ex, 200, Llm.JSON.createObjectNode().put("deleted", name).toString());
            }
            default -> send(ex, 405, error("GET, PUT or DELETE"));
        }
    }

    /** Same layout Specialist.load reads: "description: ...", the prompt, then an optional "## context" section. */
    static String render(String description, String prompt, String builtInContext) {
        StringBuilder s = new StringBuilder("description: ").append(description.replaceAll("\\s+", " ").strip()).append('\n');
        s.append(prompt.strip()).append('\n');
        if (!builtInContext.isBlank()) s.append("\n## context\n").append(builtInContext.strip()).append('\n');
        return s.toString();
    }

    private static void write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ one agentic turn

    private void turn(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, error("POST"));
            return;
        }
        String message, history;
        try {
            JsonNode body = readJson(ex);
            message = body.path("message").asString("").strip();
            history = body.path("history").asString("").strip();
        } catch (BadRequest e) {
            send(ex, e.status, error(e.getMessage()));
            return;
        }
        if (message.isBlank()) {
            send(ex, 400, error("Write a request first"));
            return;
        }
        System.out.println("agentic turn " + message);
        Progress.clear();
        Progress.note("Starting");
        try {
            Llm llm = Llm.fromEnv();
            Coordinator c = new Coordinator(llm, new Router.ByLlm(llm), "0".equals(System.getenv("OPPONENT")) ? null : new Opponent(llm),
                    Memory.fromEnv(), Specialist.load(specialists));
            String request = history.isBlank() ? message : "Conversation so far:\n" + history + "\nCurrent request:\n" + message;
            JsonNode contacts = contacts();
            Turn.Outcome outcome;
            try (MailboxClient mail = MailboxClient.open()) {
                outcome = c.handle(request, owner(contacts), mail, new MailTools(mail, contacts, "1".equals(System.getenv("RAG")) ? llm : null));
            }
            Progress.note("Routed to " + c.routed + ", " + c.contextUsed.size() + " context passage(s)");
            ObjectNode root = (ObjectNode) Llm.JSON.readTree(UiServer.payload(outcome, null, 0));
            root.put("agent", c.routed);
            ArrayNode used = root.putArray("context");
            c.contextUsed.forEach(used::add);
            ObjectNode stats = root.putObject("stats");
            c.stats.forEach(stats::put);
            Progress.note("Finished");
            send(ex, 200, root.toString());
        } catch (RuntimeException e) {
            String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            System.out.println("agentic turn error " + text);
            Progress.note("Stopped: " + text);
            send(ex, 500, error(text));
        }
    }

    /** The directory of people the mailbox tools use (lookup_contact): the benchmark's, or none. */
    private static JsonNode contacts() {
        Path file = Path.of(System.getenv().getOrDefault("CONTACTS", "benchmark/scenarios/university.json"));
        try {
            if (Files.isRegularFile(file)) return Llm.JSON.readTree(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            // fall through: no directory
        }
        return Llm.JSON.readTree("{\"people\":[]}");
    }

    /** MAIL_OWNER, else the owner of the SCENARIO file (fictional mailbox), else a neutral phrase. */
    private static String owner(JsonNode contacts) {
        String named = System.getenv("MAIL_OWNER");
        if (named != null && !named.isBlank()) return named;
        String scenario = System.getenv("SCENARIO");
        if (scenario != null && Files.isRegularFile(Path.of(scenario))) {
            try {
                String id = Llm.JSON.readTree(Files.readString(Path.of(scenario))).path("owner").asString("");
                for (JsonNode p : contacts.path("people")) {
                    if (p.path("id").asString().equals(id)) return p.path("name").asString() + " <" + p.path("email").asString() + ">";
                }
            } catch (IOException | RuntimeException e) {
                // fall through
            }
        }
        return "the mailbox owner";
    }

    // ------------------------------------------------------------------ plumbing

    private static final class BadRequest extends RuntimeException {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static JsonNode readJson(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BYTES * 2 + 1);
            if (bytes.length > MAX_BYTES * 2) throw new BadRequest(413, "Request too large");
            return Llm.JSON.readTree(bytes);
        } catch (BadRequest e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadRequest(400, "Unreadable request");
        }
    }

    private static String error(String message) {
        return Llm.JSON.createObjectNode().put("error", message).toString();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}

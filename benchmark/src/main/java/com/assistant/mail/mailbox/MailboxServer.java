package com.assistant.mail.mailbox;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP server (stdio) over one scenario's fictional mailbox. Simulated data only.
 * There is deliberately no send tool: drafts are the only write.
 * Every call is appended to the tool log, so the tool-call layer comes from the environment.
 *
 * Usage: MailboxServer <scenario.json> <tools-log.jsonl>
 */
public class MailboxServer {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<JsonNode> mails = new ArrayList<>();
    private final ArrayNode drafts = JSON.createArrayNode();
    private final PrintWriter log;

    MailboxServer(Path scenario, Path toolLog) throws IOException {
        JSON.readTree(scenario.toFile()).path("mailbox").path("mails").forEach(mails::add);
        log = new PrintWriter(new FileWriter(toolLog.toFile(), true), true);
    }

    public static void main(String[] args) throws Exception {
        MailboxServer box = new MailboxServer(Path.of(args[0]), Path.of(args[1]));
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        McpServer.sync(new StdioServerTransportProvider(mapper))
                .serverInfo("university-mailbox", "0.1.0")
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(
                        box.tool("search_mail", "Keyword search over subject, body and sender. Every word must match.",
                                schema(Map.of("query", "string"), List.of("query")), box::searchMail),
                        box.tool("read_mail", "Read one mail by id, with attachment text.",
                                schema(Map.of("id", "string"), List.of("id")), box::readMail),
                        box.tool("read_thread", "Read every mail of a thread, oldest first.",
                                schema(Map.of("threadId", "string"), List.of("threadId")), box::readThread),
                        box.tool("create_draft", "Place a draft in the drafts folder. It is never sent.",
                                schema(Map.of("to", "array", "cc", "array", "bcc", "array",
                                        "subject", "string", "body", "string", "inReplyTo", "string"),
                                        List.of("to", "subject", "body")), box::createDraft))
                .build();
        // stdio transport threads keep the JVM alive until the client closes stdin
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> schema,
                                       Function<JsonNode, JsonNode> handler) {
        return SyncToolSpecification.builder()
                .tool(Tool.builder().name(name).description(description).inputSchema(schema).build())
                .callHandler((exchange, request) -> {
                    long start = System.currentTimeMillis();
                    JsonNode args = JSON.valueToTree(request.arguments());
                    ObjectNode entry = JSON.createObjectNode().put("tool", name).set("args", args);
                    try {
                        JsonNode result = handler.apply(args);
                        logCall(entry.set("result", result), start);
                        return CallToolResult.builder().addTextContent(result.toString()).build();
                    } catch (RuntimeException e) {
                        logCall(entry.put("error", e.getMessage()), start);
                        return CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
                    }
                })
                .build();
    }

    private synchronized void logCall(ObjectNode entry, long start) {
        log.println(entry.put("ms", System.currentTimeMillis() - start));
    }

    private static Map<String, Object> schema(Map<String, String> types, List<String> required) {
        Map<String, Object> props = new LinkedHashMap<>();
        types.forEach((k, t) -> props.put(k, t.equals("array")
                ? Map.of("type", "array", "items", Map.of("type", "string"))
                : Map.of("type", t)));
        return Map.of("type", "object", "properties", props, "required", required);
    }

    JsonNode searchMail(JsonNode args) {
        String[] words = args.path("query").asString("").toLowerCase(Locale.ROOT).split("\\s+");
        ArrayNode hits = JSON.createArrayNode();
        for (JsonNode m : mails) {
            String hay = (m.path("subject").asString("") + " " + m.path("body").asString("") + " "
                    + m.path("from").asString("")).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) all &= w.isEmpty() || hay.contains(w);
            if (!all) continue;
            String body = m.path("body").asString("");
            hits.addObject()
                    .put("id", m.path("id").asString()).put("threadId", m.path("threadId").asString())
                    .put("from", m.path("from").asString()).put("subject", m.path("subject").asString())
                    .put("date", m.path("date").asString())
                    .put("snippet", body.substring(0, Math.min(100, body.length())));
        }
        return hits;
    }

    JsonNode readMail(JsonNode args) {
        String id = args.path("id").asString("");
        return mails.stream().filter(m -> m.path("id").asString().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no mail with id " + id));
    }

    JsonNode readThread(JsonNode args) {
        String threadId = args.path("threadId").asString("");
        ArrayNode thread = JSON.createArrayNode();
        mails.stream().filter(m -> m.path("threadId").asString().equals(threadId))
                .sorted((a, b) -> a.path("date").asString().compareTo(b.path("date").asString()))
                .forEach(thread::add);
        if (thread.isEmpty()) throw new IllegalArgumentException("no thread with id " + threadId);
        return thread;
    }

    JsonNode createDraft(JsonNode args) {
        if (!args.path("to").isArray() || args.path("to").isEmpty())
            throw new IllegalArgumentException("create_draft needs at least one recipient in 'to'");
        String draftId = "d" + (drafts.size() + 1);
        drafts.add(((ObjectNode) args.deepCopy()).put("draftId", draftId));
        return JSON.createObjectNode().put("draftId", draftId);
    }
}

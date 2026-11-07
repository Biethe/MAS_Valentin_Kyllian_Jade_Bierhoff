package com.assistant.mail.bench;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reference system under test. It honours the trace contract exactly like a real system would:
 * reads SCENARIO / MAILBOX_CMD / TRACE_OUT, starts the mailbox over MCP stdio, and writes a trace.
 * Instead of thinking, it replays a hand-written reference trace: tool steps are really executed
 * against the mailbox, every other step is copied as written.
 *
 * Usage (as a system command): Replayer <reference.jsonl> | Replayer <variant prefix>
 * A prefix (e.g. "good", "broken") picks the first matching reference of the scenario in SCENARIO,
 * so the replayer can stand in for a real system in ./bench run.
 */
public class Replayer {

    static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        String mailboxCmd = System.getenv("MAILBOX_CMD");
        Path traceOut = Path.of(System.getenv("TRACE_OUT"));
        var transport = new StdioClientTransport(
                ServerParameters.builder("sh").args("-c", mailboxCmd).build(), McpJsonDefaults.getMapper());
        try (McpSyncClient mail = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
             PrintWriter out = new PrintWriter(Files.newBufferedWriter(traceOut))) {
            mail.initialize();
            int step = 0;
            for (String line : Files.readAllLines(reference(args[0]))) {
                if (line.isBlank()) continue;
                ObjectNode event = (ObjectNode) JSON.readTree(line);
                event.put("step", ++step);
                if (event.path("kind").asString().equals("tool")) call(mail, event);
                else event.put("ms", 0);
                out.println(event);
            }
        }
    }

    static Path reference(String arg) throws Exception {
        if (arg.endsWith(".jsonl")) return Path.of(arg);
        String id = Path.of(System.getenv("SCENARIO")).getFileName().toString().replace(".json", "");
        return Bench.list(Bench.REFS, ".jsonl").stream()
                .filter(p -> p.getFileName().toString().startsWith(id + "." + arg)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no reference " + id + "." + arg + "*"));
    }

    private static void call(McpSyncClient mail, ObjectNode event) {
        long start = System.currentTimeMillis();
        Map<String, Object> args = JSON.convertValue(event.path("input"), Map.class);
        CallToolResult result = mail.callTool(new CallToolRequest(event.path("name").asString(), args));
        String text = result.content().isEmpty() ? "" : ((TextContent) result.content().get(0)).text();
        if (Boolean.TRUE.equals(result.isError())) event.put("error", text);
        else event.set("output", parse(text));
        event.put("ms", System.currentTimeMillis() - start);
    }

    private static JsonNode parse(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            return JSON.getNodeFactory().stringNode(text);
        }
    }
}

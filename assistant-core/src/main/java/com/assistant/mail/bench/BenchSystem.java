package com.assistant.mail.bench;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.AssistantConfig;
import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.workflow.Turn;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real assistant under the benchmark's trace contract.
 * Reads SCENARIO (request only), MAILBOX_CMD (mailbox over MCP) and TRACE_OUT, writes one JSON line per step.
 * Usage in the benchmark: {@code ./bench run --system "../run.sh bench"}.
 */
public final class BenchSystem {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private BenchSystem() {}

    public static void main(String[] args) throws Exception {
        AssistantConfig config = AssistantConfig.parse(args, System.getenv());
        config.mailbox = AssistantConfig.Mailbox.MCP;
        String request = JSON.readTree(Path.of(require("SCENARIO")).toFile()).path("request").asString();
        Path traceOut = Path.of(require("TRACE_OUT"));
        try (Assistant assistant = Assistant.create(config)) {
            long start = System.currentTimeMillis();
            Turn.Outcome outcome = assistant.ask(request);
            write(traceOut, request, outcome, config.llm == AssistantConfig.Llm.SCRIPTED ? "scripted" : config.model,
                    System.currentTimeMillis() - start);
        }
    }

    /** Trace steps for one outcome: route, facts (both optional for the benchmark) and the required output. */
    static List<Map<String, Object>> steps(String request, Turn.Outcome outcome, String model, long ms) {
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Object> instructions = new ArrayList<>();
        for (Brief brief : outcome.briefs) {
            instructions.add(Map.of("type", String.valueOf(brief.getTask()), "clear", true));
        }
        if (!instructions.isEmpty()) {
            steps.add(step("route", "planner", "atomic_instructions", Map.of("request", request),
                    Map.of("instructions", instructions), model, 0));
        }
        List<Object> items = new ArrayList<>();
        for (Brief brief : outcome.briefs) {
            for (Information fact : brief.getFacts()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", String.valueOf(fact.getKind()));
                item.put("text", fact.getBody());
                if (fact.getSourceEmailId() != null) item.put("sourceMailId", "m" + fact.getSourceEmailId());
                items.add(item);
            }
        }
        for (var flag : outcome.flagged) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "SUSPICIOUS_INSTRUCTION");
            item.put("text", flag.text());
            if (flag.emailId() != null) item.put("sourceMailId", "m" + flag.emailId());
            items.add(item);
        }
        if (!items.isEmpty()) {
            steps.add(step("facts", "planner", "atomic_information", Map.of(), Map.of("items", items), model, 0));
        }
        Map<String, Object> output = new LinkedHashMap<>();
        String actor = "planner", name = "ask_user";
        if (!outcome.drafts.isEmpty()) {
            Draft d = outcome.drafts.get(0);
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("to", d.getRecipients());
            draft.put("cc", d.getCc());
            draft.put("bcc", d.getBcc());
            draft.put("subject", d.getSubject());
            draft.put("body", d.getBody());
            Brief brief = outcome.briefs.isEmpty() ? null : outcome.briefs.get(0);
            if (brief != null && brief.getReplyToId() != null) draft.put("inReplyTo", "m" + brief.getReplyToId());
            output.put("draft", draft);
            actor = "drafter";
            name = "draft";
        } else if (!outcome.summaries.isEmpty()) {
            output.put("summary", outcome.summaries.get(0).getBody());
            actor = "summarizer";
            name = "summarize";
        } else if (!outcome.questions.isEmpty()) {
            output.put("question", outcome.questions.get(0).getText());
        } else {
            output.put("question", String.join(" ", outcome.refused) + String.join(" ", outcome.chats));
        }
        List<String> said = new ArrayList<>(outcome.warnings);
        said.addAll(outcome.refused);
        said.addAll(outcome.chats);
        if (!said.isEmpty()) output.put("message", String.join(" ", said));
        steps.add(step("output", actor, name, Map.of(), output, model, ms));
        return steps;
    }

    static void write(Path out, String request, Turn.Outcome outcome, String model, long ms) throws IOException {
        List<Map<String, Object>> steps = steps(request, outcome, model, ms);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            int n = 0;
            for (Map<String, Object> s : steps) {
                s.put("step", ++n);
                w.println(JSON.writeValueAsString(s));
            }
        }
    }

    private static Map<String, Object> step(String layer, String actor, String name, Object input, Object output,
                                            String model, long ms) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("layer", layer);
        s.put("actor", actor);
        s.put("kind", "llm");
        s.put("name", name);
        s.put("input", input);
        s.put("output", output);
        s.put("model", model);
        s.put("ms", ms);
        return s;
    }

    private static String require(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) throw new IllegalStateException(name + " is not set (run me through ./bench run)");
        return v;
    }

    static JsonNode read(String json) { return JSON.readTree(json); }
}

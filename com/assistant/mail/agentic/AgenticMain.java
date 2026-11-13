package com.assistant.mail.agentic;

import com.assistant.mail.service.mcp.MailboxClient;
import com.assistant.mail.workflow.turn.TraceFile;
import com.assistant.mail.workflow.turn.Turn;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Benchmark entry of the agentic system: one scenario in, one trace out, plus a sidecar
 * {@code <trace>.agentic.json} with the route, the negotiation counts and every agent message.
 * Env: SPECIALISTS (dir, default specialists/ next to the repo root), CONTACTS, MEMORY_FILE, OPPONENT=0 to remove the opponent,
 * RAG=1 to add semantic_search over the mailbox.
 */
public final class AgenticMain {
    public static void main(String[] args) throws Exception {
        Path scenario = Path.of(System.getenv("SCENARIO")), trace = Path.of(System.getenv("TRACE_OUT"));
        JsonNode sc = Llm.JSON.readTree(scenario.toFile());
        String request = sc.path("request").asString("");
        JsonNode contacts = Llm.JSON.readTree(Path.of(System.getenv().getOrDefault("CONTACTS", "scenarios/university.json")).toFile());
        String owner = sc.path("owner").asString("");
        for (JsonNode p : contacts.path("people"))
            if (p.path("id").asString().equals(owner)) owner = p.path("name").asString() + " <" + p.path("email").asString() + ">";

        Llm llm = Llm.fromEnv();
        Memory memory = Memory.fromEnv();
        Coordinator c = new Coordinator(llm, new Router.ByLlm(llm), "0".equals(System.getenv("OPPONENT")) ? null : new Opponent(llm),
                memory, Specialist.load(Path.of(System.getenv().getOrDefault("SPECIALISTS", "../specialists"))));
        Turn.Outcome outcome;
        try (MailboxClient mail = MailboxClient.open()) {
            outcome = c.handle(request, owner, mail, new MailTools(mail, contacts, "1".equals(System.getenv("RAG")) ? llm : null));
        }
        TraceFile.write(trace, request, outcome);
        ObjectNode side = Llm.JSON.createObjectNode().put("specialist", c.routed);
        c.stats.forEach(side::put);
        var msgs = side.putArray("messages");
        c.log.forEach(m -> msgs.add(m.toString()));
        Files.writeString(Path.of(trace + ".agentic.json"), side.toPrettyString());
    }
}

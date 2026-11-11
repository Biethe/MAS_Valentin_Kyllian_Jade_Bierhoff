package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.service.agent.OllamaAgent;
import com.assistant.mail.service.mcp.MailboxClient;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** The system under test: one scenario in, one trace out. */
public final class BenchTurn {
    private BenchTurn() {}

    public static void main(String[] args) throws Exception {
        String scenario = System.getenv("SCENARIO");
        String traceOut = System.getenv("TRACE_OUT");
        if (scenario == null || scenario.isBlank() || traceOut == null || traceOut.isBlank()) {
            throw new IllegalStateException("SCENARIO and TRACE_OUT are required");
        }
        String request = JsonMapper.builder().build().readTree(Path.of(scenario).toFile()).path("request").asString("");
        OllamaAgent model = new OllamaAgent();
        LongTermMemory memory = MemoryFile.open();
        try (MailboxClient mail = MailboxClient.open()) {
            Turn turn = new Turn(new Planner(mail, model),
                    new WriterWorkflow(model, mail, memory, null),
                    new SummaryWorkflow(model, memory, null));
            Turn.Outcome outcome = turn.run(new Request(request, java.util.List.of()), memory);
            TraceFile.write(Path.of(traceOut), request, outcome);
        }
        MemoryFile.saveIfSet(memory);
    }
}

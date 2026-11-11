package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.service.agent.OllamaAgent;
import com.assistant.mail.service.mcp.MailboxClient;
import java.nio.file.Path;
import java.util.List;

/** One request against the mailbox selected by {@code MAIL_MCP}. Prints the answer. */
public final class MailMain {
    private static final Path MEMORY = Path.of("memory.json");

    private MailMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || String.join(" ", args).isBlank()) {
            throw new IllegalStateException("usage: MailMain <request>");
        }
        OllamaAgent model = new OllamaAgent();
        LongTermMemory memory = MemoryFile.load(MEMORY);
        try (MailboxClient mail = MailboxClient.open()) {
            Turn turn = new Turn(new Planner(mail, model),
                    new WriterWorkflow(model, mail, memory, null),
                    new SummaryWorkflow(model, memory, null));
            Turn.Outcome outcome = turn.run(new Request(String.join(" ", args), List.of()), memory);
            System.out.println(TraceFile.text(outcome));
        }
        MemoryFile.save(MEMORY, memory);
    }
}

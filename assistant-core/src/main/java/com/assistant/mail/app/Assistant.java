package com.assistant.mail.app;

import com.assistant.mail.llm.OllamaChat;
import com.assistant.mail.llm.ScriptedModels;
import com.assistant.mail.mailbox.InMemoryMailbox;
import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.ask.QuestionReason;
import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;
import com.assistant.mail.service.mcp.MailboxClient;
import com.assistant.mail.workflow.Planner;
import com.assistant.mail.workflow.SummaryWorkflow;
import com.assistant.mail.workflow.Turn;
import com.assistant.mail.workflow.WriterWorkflow;
import java.util.List;

/** The assistant behind every front end (terminal, web, benchmark): one Turn over one mailbox. */
public final class Assistant implements AutoCloseable {
    private final Turn turn;
    private final MailClientMcp mail;
    private final LongTermMemory memory = new LongTermMemory();
    private final String description;
    private final AutoCloseable onClose;
    private Turn.Outcome last;

    public Assistant(MailClientMcp mail, PlannerAgentClient planner, DrafterAgentClient drafter,
                     SummarizerAgentClient summarizer, String description, AutoCloseable onClose) {
        this.mail = mail;
        this.description = description;
        this.onClose = onClose;
        this.turn = new Turn(new Planner(mail, planner),
                new WriterWorkflow(drafter, mail, memory, null),
                new SummaryWorkflow(summarizer, memory, null));
    }

    /** Builds the assistant the config asks for. Fails early, with a readable message, if a piece is missing. */
    public static Assistant create(AssistantConfig config) {
        MailClientMcp mail;
        AutoCloseable close = () -> { };
        if (config.mailbox == AssistantConfig.Mailbox.MCP) {
            MailboxClient client = MailboxClient.open();
            mail = client;
            close = client;
        } else {
            mail = InMemoryMailbox.demo();
        }
        String mailboxName = config.mailbox == AssistantConfig.Mailbox.MCP ? "MCP mailbox (MAILBOX_CMD)" : "demo mailbox";
        if (config.llm == AssistantConfig.Llm.SCRIPTED) {
            ScriptedModels models = new ScriptedModels();
            return new Assistant(mail, models, models, models, "scripted models + " + mailboxName, close);
        }
        OllamaChat probe = new OllamaChat(config.ollamaUrl, config.model);
        if (!probe.reachable()) {
            throw new IllegalStateException("Ollama is not reachable at " + config.ollamaUrl
                    + ". Start it (`ollama serve`, `ollama pull " + config.model + "`) or use --mock.");
        }
        String model = OllamaChat.pick(config.model, OllamaChat.installed(config.ollamaUrl));
        if (model == null) {
            throw new IllegalStateException("No suitable model installed. Run `ollama pull " + config.model
                    + "` (installed: " + OllamaChat.installed(config.ollamaUrl) + ").");
        }
        if (!model.equals(config.model)) {
            System.err.println("Model " + config.model + " is not installed: using " + model
                    + " (`ollama pull " + config.model + "` for the reference model).");
        }
        com.assistant.mail.llm.TolerantReplies tolerant =
                com.assistant.mail.llm.TolerantReplies.around(new OllamaChat(config.ollamaUrl, model));
        return new Assistant(mail, tolerant.planner(), tolerant.drafter(), tolerant.summarizer(),
                model + " + " + mailboxName, close);
    }

    /** Handles one user message. Calls are serialised: the memory and the mailbox are shared. */
    public synchronized Turn.Outcome ask(String text) {
        Turn.Outcome outcome = turn.run(new Request(text, List.of()), memory);
        boolean silent = outcome.questions.isEmpty() && outcome.refused.isEmpty() && outcome.chats.isEmpty()
                && outcome.drafts.isEmpty() && outcome.summaries.isEmpty();
        if (silent && text != null && !text.isBlank()) {
            // A model that ignores the block format yields nothing: say so instead of staying silent.
            outcome.questions.add(new Question(
                    "Je n'ai pas pu interpréter la demande : le modèle n'a pas respecté le format attendu. "
                            + "Reformulez, ou utilisez un modèle plus capable (ex. --model llama3.1:8b).",
                    QuestionReason.UNCLEAR, List.of()));
        }
        last = outcome;
        return last;
    }

    public synchronized Turn.Outcome last() { return last; }

    public MailClientMcp mail() { return mail; }

    public LongTermMemory memory() { return memory; }

    public String description() { return description; }

    /** Drafts saved by the demo mailbox; empty for other mailboxes. */
    public List<Draft> savedDrafts() {
        return mail instanceof InMemoryMailbox box ? box.drafts() : List.of();
    }

    @Override
    public void close() {
        try {
            onClose.close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

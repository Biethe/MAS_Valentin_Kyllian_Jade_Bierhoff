package fr.mas.email.agent;

import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryAgentTest {

    private static final EmailMessage EMAIL = new EmailMessage(
            "prof@univ.fr", "me@example.com", "Project deadline",
            "The project is due Friday. Please send your report.", "t1");

    private static final ContextProvider CONTEXT = category -> "";

    @Test
    void parsesJsonOutput() {
        SummaryAgent agent = new SummaryAgent(
                llm -> "{\"summary\":\"Deadline Friday.\",\"needs_reply\":true,\"draft\":\"I will send it.\"}",
                CONTEXT, new PromptRepository());

        SummaryResult result = agent.summarize(EMAIL);

        assertEquals("Deadline Friday.", result.summary());
        assertTrue(result.needsReply());
        assertEquals("I will send it.", result.draft());
    }

    @Test
    void parsesLabelledTextOutput() {
        SummaryAgent agent = new SummaryAgent(
                llm -> "Summary: The project is due Friday.\n\n"
                        + "Needs Reply: true\n\n"
                        + "Draft: \"I will send my report by Friday.\"",
                CONTEXT, new PromptRepository());

        SummaryResult result = agent.summarize(EMAIL);

        assertEquals("The project is due Friday.", result.summary());
        assertTrue(result.needsReply());
        assertEquals("I will send my report by Friday.", result.draft());
    }

    @Test
    void doesNotMarkReplyWhenNoDraft() {
        SummaryAgent agent = new SummaryAgent(
                llm -> "Summary: A newsletter.\nNeeds Reply: false",
                CONTEXT, new PromptRepository());

        EmailMessage newsletter = new EmailMessage(
                "news@example.com", "me@example.com", "Weekly newsletter",
                "Here are this week's updates.", "t2");
        SummaryResult result = agent.summarize(newsletter);

        assertEquals("A newsletter.", result.summary());
        assertFalse(result.needsReply());
        assertEquals("", result.draft());
    }
}

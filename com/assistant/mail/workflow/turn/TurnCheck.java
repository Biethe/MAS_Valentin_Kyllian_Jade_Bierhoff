package com.assistant.mail.workflow.turn;

import com.assistant.mail.workflow.planner.Planner;
import com.assistant.mail.workflow.summary.SummaryWorkflow;
import com.assistant.mail.workflow.writer.WriterWorkflow;

import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.util.List;
import java.util.Locale;

/** Planner branches land on the writer or the summarizer, and a bad draft is not saved. */
public final class TurnCheck {
    public static void main(String[] args) {
        Email email = new Email();
        email.setId(1L);
        email.setSubject("RDV");
        email.setBody("Réunion vendredi à 10h.");
        int[] saves = {0};
        MailClientMcp mail = new MailClientMcp() {
            public List<Email> retrieveRelevantEmails(String query) { return List.of(email); }
            public List<EmailThread> retrieveRelevantThreads(String query) { return List.of(); }
            public List<AttachmentFile> retrieveAttachments(Email ignored) { return List.of(); }
            public List<AttachmentFile> retrieveAttachments(EmailThread ignored) { return List.of(); }
            public Long createDraft(List<String> to, List<String> cc, List<String> bcc, String subject, String body, Long inReplyTo) {
                saves[0]++;
                return 7L;
            }
        };
        Turn turn = turn(mail, "ada@u.test", "Réunion vendredi à 10h.");
        Turn.Outcome summary = turn.run(new Request("Résume le mail", List.of()), new LongTermMemory());
        assert summary.summaries.size() == 1;
        assert summary.summaries.get(0).getBody().contains("Réunion");
        assert summary.progress.get(0).startsWith("1/1");
        assert summary.drafts.isEmpty();

        Turn.Outcome reply = turn.run(new Request("Réponds à Ada", List.of()), new LongTermMemory());
        assert reply.drafts.size() == 1;
        assert Long.valueOf(7L).equals(reply.drafts.get(0).getId());
        assert reply.progress.get(0).startsWith("2/2");
        assert saves[0] == 1;

        Turn blocked = turn(mail, "evil@x.test", "non");
        Turn.Outcome refused = blocked.run(new Request("Réponds à Ada", List.of()), new LongTermMemory());
        assert refused.drafts.isEmpty();
        assert refused.questions.size() == 1;
        assert refused.questions.get(0).getText().contains("not in the brief");
        assert saves[0] == 1;
        System.out.println("TurnCheck ok");
    }

    private static Turn turn(MailClientMcp mail, String recipient, String fact) {
        PlannerAgentClient planner = (system, user) -> {
            if (system.equals(PlannerSystemPrompt.SPLIT)) return "body:\n" + user.strip();
            if (system.equals(PlannerSystemPrompt.ROUTE)) {
                return user.toLowerCase(Locale.ROOT).contains("résume")
                        ? "type:\nSUMMARIZE\n\nclear:\nyes" : "type:\nREPLY\n\nclear:\nyes";
            }
            if (system.equals(PlannerSystemPrompt.TOOLS)) {
                return user.contains("email:") ? "done:\nyes" : "name:\nsearch_mail\n\nquery:\nAda";
            }
            if (system.equals(PlannerSystemPrompt.LOCATE)) return "email:\n1";
            if (system.equals(PlannerSystemPrompt.READ)) return "body:\nRéunion vendredi à 10h.\n\nkind:\nINFORMATIVE";
            if (system.equals(PlannerSystemPrompt.BRIEF)) return "to:\nada@u.test\n\nfact:\nRéunion vendredi à 10h.";
            if (system.equals(PlannerSystemPrompt.CRITIC)) return "";
            if (system.equals(PlannerSystemPrompt.CHECKLIST)) {
                String factItem = """
                        part:
                        FACT

                        expected:
                        Réunion vendredi à 10h.
                        """;
                if (user.contains("SUMMARIZE")) return factItem;
                return """
                        part:
                        TO

                        expected:
                        ada@u.test

                        """ + factItem;
            }
            throw new AssertionError(system);
        };
        DrafterAgentClient drafter = (system, user) -> {
            if (system.equals(DrafterSystemPrompt.TEXT) || system.equals(DrafterSystemPrompt.REWRITE)) {
                return "recipients:\n" + recipient + "\n\ncc:\n\nbcc:\n\nsubject:\nRDV\n\nbody:\n" + fact;
            }
            if (system.equals(DrafterSystemPrompt.MARK) || system.equals(DrafterSystemPrompt.CRITIC)) return "";
            throw new AssertionError(system);
        };
        SummarizerAgentClient summarizer = (system, user) -> {
            if (system.equals(SummarizerSystemPrompt.TEXT)) return "Réunion vendredi à 10h.";
            if (system.equals(SummarizerSystemPrompt.MARK) || system.equals(SummarizerSystemPrompt.CRITIC)) return "";
            throw new AssertionError(system);
        };
        return new Turn(new Planner(mail, planner),
                new WriterWorkflow(drafter, mail, new LongTermMemory(), null),
                new SummaryWorkflow(summarizer, new LongTermMemory(), null));
    }
}

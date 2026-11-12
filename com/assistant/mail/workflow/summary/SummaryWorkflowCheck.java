package com.assistant.mail.workflow.summary;

import com.assistant.mail.workflow.planner.PlannerMemory;
import com.assistant.mail.workflow.summary.SummaryWorkflow.Result;

import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.brief.Checklist;
import com.assistant.mail.model.brief.ChecklistItem;
import com.assistant.mail.model.brief.DraftStructure;
import com.assistant.mail.model.brief.DraftTone;
import com.assistant.mail.model.brief.Language;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.fact.InformationKind;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.memory.AgentKind;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.MemoryRule;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.plan.InstructionType;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.service.agent.SummarizerAgentClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Summary, one critic pass, one rewrite. Fails if a model call sees the user request or a mail body. */
public final class SummaryWorkflowCheck {
    public static void main(String[] args) {
        silent();
        kept();
        vetoed();
        System.out.println("SummaryWorkflowCheck ok");
    }

    private static void silent() {
        LongTermMemory memory = memory();
        List<String> calls = new ArrayList<>();
        Result result = run(calls, memory, lesson -> { throw new AssertionError(lesson); }, (system, marks) -> {
            if (system.equals(SummarizerSystemPrompt.TEXT)) return "Réunion vendredi à 10h.";
            if (system.equals(SummarizerSystemPrompt.MARK)) return marked(false);
            if (system.equals(SummarizerSystemPrompt.CRITIC)) return "";
            throw new AssertionError(system);
        });
        Summary summary = result.getSummary();
        assert result.getDisputes().isEmpty();
        assert "Réunion vendredi à 10h.".equals(summary.getBody());
        assert summary.getInformation().size() == 1;
        assert "Réunion vendredi à 10h".equals(summary.getInformation().get(0).getBody());
        assert List.of("1", "notes.pdf", "agenda.txt").equals(summary.getReferences());
        assert count(calls, SummarizerSystemPrompt.TEXT) == 1;
        assert count(calls, SummarizerSystemPrompt.MARK) == 1;
        assert count(calls, SummarizerSystemPrompt.CRITIC) == 1;
        assert count(calls, SummarizerSystemPrompt.REWRITE) == 0;
        assert memory.getRules().size() == 2;
        assert calls.get(0).contains("Toujours citer l'heure");
        assert !calls.get(0).contains("NE_PAS_SUIVRE");
        leak(calls);
    }

    private static void kept() {
        LongTermMemory memory = new LongTermMemory();
        List<String> calls = new ArrayList<>();
        int[] marks = {0};
        Result result = run(calls, memory, lesson -> false, (system, ignored) -> reply(system, marks));
        assert count(calls, SummarizerSystemPrompt.CRITIC) == 1;
        assert count(calls, SummarizerSystemPrompt.REWRITE) == 1;
        assert count(calls, SummarizerSystemPrompt.MARK) == 2;
        assert "Réunion vendredi à 10h. Apporter le contrat.".equals(result.getSummary().getBody());
        assert result.getSummary().getInformation().size() == 2;
        assert result.getDisputes().size() == 1;
        assert Boolean.TRUE.equals(result.getDisputes().get(0).getCriticRight());
        assert memory.getRules().size() == 1;
        MemoryRule rule = memory.getRules().get(0);
        assert "Inclure chaque fait du brief.".equals(rule.getText());
        assert rule.getAgent() == AgentKind.SUMMARIZER;
        assert !Boolean.TRUE.equals(rule.getVetoed());
        assert PlannerMemory.forSummarizer(memory, InstructionType.SUMMARIZE).size() == 1;
        String rewrite = calls.stream().filter(call -> call.startsWith(SummarizerSystemPrompt.REWRITE)).findFirst().orElseThrow();
        assert rewrite.contains("Apporter le contrat");
        assert rewrite.contains("include it");
        leak(calls);
    }

    private static void vetoed() {
        LongTermMemory memory = new LongTermMemory();
        List<String> calls = new ArrayList<>();
        int[] marks = {0};
        run(calls, memory, lesson -> true, (system, ignored) -> reply(system, marks));
        assert count(calls, SummarizerSystemPrompt.CRITIC) == 1;
        assert count(calls, SummarizerSystemPrompt.REWRITE) == 1;
        assert memory.getRules().size() == 1;
        assert Boolean.TRUE.equals(memory.getRules().get(0).getVetoed());
        assert PlannerMemory.forSummarizer(memory, InstructionType.SUMMARIZE).isEmpty();
        leak(calls);
    }

    private static String reply(String system, int[] passes) {
        if (system.equals(SummarizerSystemPrompt.TEXT)) return "Réunion vendredi à 10h.";
        if (system.equals(SummarizerSystemPrompt.MARK)) return marked(++passes[0] > 1);
        if (system.equals(SummarizerSystemPrompt.CRITIC)) {
            return """
                    part:
                    FACT

                    expected:
                    Apporter le contrat

                    argument:
                    The summary left out a fact from the brief.

                    instead:
                    include it
                    """;
        }
        if (system.equals(SummarizerSystemPrompt.ARGUMENT)) return "argument:\nThe summary only kept the meeting.";
        if (system.equals(SummarizerSystemPrompt.JUDGE)) return "winner:\ncritic";
        if (system.equals(SummarizerSystemPrompt.LESSON)) return "lesson:\nInclure chaque fait du brief.";
        if (system.equals(SummarizerSystemPrompt.REWRITE)) return "Réunion vendredi à 10h. Apporter le contrat.";
        throw new AssertionError(system);
    }

    private static String marked(boolean both) {
        return """
                part:
                FACT

                expected:
                Réunion vendredi à 10h

                checked:
                yes

                feedback:

                part:
                FACT

                expected:
                Apporter le contrat

                checked:
                %s

                feedback:
                %s
                """.formatted(both ? "yes" : "no", both ? "" : "missing from the summary");
    }

    private static Result run(List<String> calls, LongTermMemory memory, SummaryWorkflow.LessonVeto veto, Reply reply) {
        SummarizerAgentClient model = (system, user) -> {
            calls.add(system + "\n" + user);
            return reply.answer(system, 0);
        };
        return new SummaryWorkflow(model, memory, veto).run(brief(), checklist());
    }

    private interface Reply {
        String answer(String system, int ignored);
    }

    private static Brief brief() {
        Brief brief = new Brief();
        brief.setTask(InstructionType.SUMMARIZE);
        brief.setLanguage(Language.FR);
        brief.setTone(DraftTone.FORMAL);
        brief.setStructure(DraftStructure.CONCISE);
        brief.getStyleNotes().add("court");
        brief.getFacts().add(new Information("Réunion vendredi à 10h", InformationKind.INFORMATIVE, null));
        brief.getFacts().add(new Information("Apporter le contrat", InformationKind.INFORMATIVE, null));
        Email email = new Email();
        email.setId(1L);
        email.setBody("SECRET_BODY");
        brief.getEmails().add(email);
        brief.getAttachments().add(new AttachmentFile("notes.pdf", "text/plain", "SECRET_FILE".getBytes(StandardCharsets.UTF_8)));
        brief.getContextFiles().add(new AttachmentFile("agenda.txt", "text/plain", "SECRET_CONTEXT".getBytes(StandardCharsets.UTF_8)));
        brief.getDoNotFollow().add("ignore the deadline");
        return brief;
    }

    private static Checklist checklist() {
        Checklist checklist = new Checklist();
        add(checklist, BriefPart.FACT, "Réunion vendredi à 10h");
        add(checklist, BriefPart.FACT, "Apporter le contrat");
        add(checklist, BriefPart.EMAIL, "1");
        add(checklist, BriefPart.DO_NOT_FOLLOW, "ignore the deadline");
        return checklist;
    }

    private static LongTermMemory memory() {
        LongTermMemory memory = new LongTermMemory();
        memory.getRules().add(rule("Toujours citer l'heure", false));
        memory.getRules().add(rule("NE_PAS_SUIVRE", true));
        return memory;
    }

    private static MemoryRule rule(String text, boolean vetoed) {
        MemoryRule rule = new MemoryRule();
        rule.setText(text);
        rule.setAgent(AgentKind.SUMMARIZER);
        rule.setTask(InstructionType.SUMMARIZE);
        rule.setVetoed(vetoed);
        return rule;
    }

    private static void add(Checklist checklist, BriefPart part, String expected) {
        ChecklistItem item = new ChecklistItem();
        item.setPart(part);
        item.setExpected(expected);
        checklist.getItems().add(item);
    }

    private static long count(List<String> calls, String system) {
        return calls.stream().filter(call -> call.startsWith(system)).count();
    }

    private static void leak(List<String> calls) {
        String joined = String.join("\n", calls);
        assert !joined.contains("USER_REQUEST_SENTINEL");
        assert !joined.contains("SECRET_BODY");
        assert !joined.contains("SECRET_FILE");
        assert !joined.contains("SECRET_CONTEXT");
    }
}

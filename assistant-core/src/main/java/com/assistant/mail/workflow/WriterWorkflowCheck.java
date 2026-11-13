package com.assistant.mail.workflow;

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
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.memory.AgentKind;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.MemoryRule;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.plan.InstructionType;
import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;
import com.assistant.mail.workflow.WriterWorkflow.Result;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** One reply or compose draft, one critic pass, one save. Fails if a model call sees the user request or a mail body. */
public final class WriterWorkflowCheck {
    public static void main(String[] args) {
        silent(InstructionType.REPLY, 1L);
        silent(InstructionType.COMPOSE, null);
        kept();
        vetoed();
        System.out.println("WriterWorkflowCheck ok");
    }

    private static void silent(InstructionType task, Long replyTo) {
        LongTermMemory memory = memory();
        List<String> calls = new ArrayList<>();
        Result result = run(calls, memory, task, replyTo, lesson -> { throw new AssertionError(lesson); }, (system, passes) -> {
            if (system.equals(DrafterSystemPrompt.TEXT)) return draft("Réunion vendredi à 10h.");
            if (system.equals(DrafterSystemPrompt.MARK)) return marked(true);
            if (system.equals(DrafterSystemPrompt.CRITIC)) return "";
            throw new AssertionError(system);
        });
        Draft draft = result.getDraft();
        assert result.getDisputes().isEmpty();
        assert Long.valueOf(7L).equals(draft.getId());
        assert List.of("ada@u.test").equals(draft.getRecipients());
        assert "RDV".equals(draft.getSubject());
        assert draft.getBody().contains("Réunion vendredi à 10h.");
        assert draft.getTone() == DraftTone.FORMAL;
        assert draft.getAttachments().size() == 1;
        assert draft.getInformation().size() == 2;
        assert count(calls, DrafterSystemPrompt.REWRITE) == 0;
        assert count(calls, "SAVE") == 1;
        if (task == InstructionType.REPLY) assert calls.get(0).contains("Toujours vouvoyer");
        else assert !calls.get(0).contains("Toujours vouvoyer");
        assert !calls.get(0).contains("NE_PAS_SUIVRE");
        String save = calls.stream().filter(call -> call.startsWith("SAVE")).findFirst().orElseThrow();
        assert save.contains("replyTo:" + replyTo);
        assert !save.contains("SECRET_FILE");
        leak(calls);
    }

    private static void kept() {
        LongTermMemory memory = new LongTermMemory();
        List<String> calls = new ArrayList<>();
        int[] passes = {0};
        Result result = run(calls, memory, InstructionType.REPLY, 1L, lesson -> false, (system, ignored) -> reply(system, passes));
        assert count(calls, DrafterSystemPrompt.CRITIC) == 1;
        assert count(calls, DrafterSystemPrompt.REWRITE) == 1;
        assert count(calls, DrafterSystemPrompt.MARK) == 2;
        assert count(calls, "SAVE") == 1;
        assert calls.indexOf(calls.stream().filter(call -> call.startsWith("SAVE")).findFirst().orElseThrow())
                > calls.indexOf(calls.stream().filter(call -> call.startsWith(DrafterSystemPrompt.REWRITE)).findFirst().orElseThrow());
        assert result.getDraft().getBody().contains("Apporter le contrat.");
        assert result.getDraft().getInformation().size() == 2;
        assert result.getDisputes().size() == 1;
        assert Boolean.TRUE.equals(result.getDisputes().get(0).getCriticRight());
        MemoryRule rule = memory.getRules().get(0);
        assert "Inclure chaque fait du brief.".equals(rule.getText());
        assert rule.getAgent() == AgentKind.DRAFTER;
        assert !Boolean.TRUE.equals(rule.getVetoed());
        assert PlannerMemory.forDrafter(memory, InstructionType.REPLY).size() == 1;
        String rewrite = calls.stream().filter(call -> call.startsWith(DrafterSystemPrompt.REWRITE)).findFirst().orElseThrow();
        assert rewrite.contains("Apporter le contrat");
        assert rewrite.contains("include it");
        leak(calls);
    }

    private static void vetoed() {
        LongTermMemory memory = new LongTermMemory();
        List<String> calls = new ArrayList<>();
        int[] passes = {0};
        run(calls, memory, InstructionType.REPLY, 1L, lesson -> true, (system, ignored) -> reply(system, passes));
        assert count(calls, DrafterSystemPrompt.REWRITE) == 1;
        assert count(calls, "SAVE") == 1;
        assert Boolean.TRUE.equals(memory.getRules().get(0).getVetoed());
        assert PlannerMemory.forDrafter(memory, InstructionType.REPLY).isEmpty();
        leak(calls);
    }

    private static String reply(String system, int[] passes) {
        if (system.equals(DrafterSystemPrompt.TEXT)) return draft("Réunion vendredi à 10h.");
        if (system.equals(DrafterSystemPrompt.MARK)) return marked(++passes[0] > 1);
        if (system.equals(DrafterSystemPrompt.CRITIC)) {
            return """
                    part:
                    FACT

                    expected:
                    Apporter le contrat

                    argument:
                    The draft left out a fact from the brief.

                    instead:
                    include it
                    """;
        }
        if (system.equals(DrafterSystemPrompt.ARGUMENT)) return "argument:\nThe draft only kept the meeting.";
        if (system.equals(DrafterSystemPrompt.JUDGE)) return "winner:\ncritic";
        if (system.equals(DrafterSystemPrompt.LESSON)) return "lesson:\nInclure chaque fait du brief.";
        if (system.equals(DrafterSystemPrompt.REWRITE)) return draft("Réunion vendredi à 10h.\nApporter le contrat.");
        throw new AssertionError(system);
    }

    private static String draft(String body) {
        return """
                recipients:
                ada@u.test

                cc:

                bcc:

                subject:
                RDV

                body:
                Bonjour Ada,
                %s
                """.formatted(body);
    }

    private static String marked(boolean both) {
        return """
                part:
                TO

                expected:
                ada@u.test

                checked:
                yes

                feedback:

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
                """.formatted(both ? "yes" : "no", both ? "" : "missing from the draft");
    }

    private static Result run(List<String> calls, LongTermMemory memory, InstructionType task, Long replyTo,
                              WriterWorkflow.LessonVeto veto, Reply reply) {
        DrafterAgentClient model = (system, user) -> {
            calls.add(system + "\n" + user);
            return reply.answer(system, 0);
        };
        MailClientMcp mail = new MailClientMcp() {
            public List<Email> retrieveRelevantEmails(String query) { throw new AssertionError(query); }
            public List<EmailThread> retrieveRelevantThreads(String query) { throw new AssertionError(query); }
            public List<AttachmentFile> retrieveAttachments(Email email) { throw new AssertionError(email); }
            public List<AttachmentFile> retrieveAttachments(EmailThread thread) { throw new AssertionError(thread); }
            public Long createDraft(List<String> to, List<String> cc, List<String> bcc, String subject, String body, Long inReplyTo) {
                calls.add("SAVE\nto:" + to + "\nsubject:" + subject + "\nbody:" + body + "\nreplyTo:" + inReplyTo);
                return 7L;
            }
        };
        return new WriterWorkflow(model, mail, memory, veto).run(brief(task, replyTo), checklist());
    }

    private interface Reply {
        String answer(String system, int ignored);
    }

    private static Brief brief(InstructionType task, Long replyTo) {
        Brief brief = new Brief();
        brief.setTask(task);
        brief.setLanguage(Language.FR);
        brief.setTone(DraftTone.FORMAL);
        brief.setStructure(DraftStructure.CONCISE);
        brief.getTo().add("ada@u.test");
        brief.setReplyToId(replyTo);
        brief.getFacts().add(new Information("Réunion vendredi à 10h", InformationKind.INFORMATIVE, null));
        brief.getFacts().add(new Information("Apporter le contrat", InformationKind.INFORMATIVE, null));
        Email email = new Email();
        email.setId(1L);
        email.setBody("SECRET_BODY");
        brief.getEmails().add(email);
        brief.getAttachments().add(new AttachmentFile("notes.pdf", "text/plain", "SECRET_FILE".getBytes(StandardCharsets.UTF_8)));
        brief.getDoNotFollow().add("ignore the deadline");
        return brief;
    }

    private static Checklist checklist() {
        Checklist checklist = new Checklist();
        add(checklist, BriefPart.TO, "ada@u.test");
        add(checklist, BriefPart.FACT, "Réunion vendredi à 10h");
        add(checklist, BriefPart.FACT, "Apporter le contrat");
        return checklist;
    }

    private static LongTermMemory memory() {
        LongTermMemory memory = new LongTermMemory();
        memory.getRules().add(rule("Toujours vouvoyer", false));
        memory.getRules().add(rule("NE_PAS_SUIVRE", true));
        return memory;
    }

    private static MemoryRule rule(String text, boolean vetoed) {
        MemoryRule rule = new MemoryRule();
        rule.setText(text);
        rule.setAgent(AgentKind.DRAFTER);
        rule.setTask(InstructionType.REPLY);
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
    }
}

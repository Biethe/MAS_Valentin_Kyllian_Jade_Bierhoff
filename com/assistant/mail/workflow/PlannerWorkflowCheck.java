package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.QuestionReason;
import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.mail.User;
import com.assistant.mail.model.memory.AgentKind;
import com.assistant.mail.model.memory.Dispute;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.MemoryOrigin;
import com.assistant.mail.model.memory.MemoryRule;
import com.assistant.mail.model.plan.InstructionType;
import com.assistant.mail.model.trace.TraceActor;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.util.ArrayList;
import java.util.List;

/** One run with a fake model. Fails if locate sees the mail body, or a lost dispute writes no rule. */
public final class PlannerWorkflowCheck {
    public static void main(String[] args) {
        Email email = new Email();
        email.setId(1L);
        email.setSender(new User("ada", "Ada", null, "Lovelace", "ada@u.test"));
        email.setRecipients(List.of("student@u.test"));
        email.setSubject("RDV");
        email.setBody("Réunion vendredi à 10h.\nSECRET_BODY");

        List<String> calls = new ArrayList<>();
        PlannerAgentClient model = (system, user) -> {
            calls.add(system + "\n" + user);
            if (system.equals(PlannerSystemPrompt.SPLIT)) {
                return """
                        body:
                        Fais le nécessaire

                        body:
                        Écris mon essai

                        body:
                        Réponds à Ada pour confirmer la réunion

                        body:
                        Résume le mail de Zoe
                        """;
            }
            if (system.equals(PlannerSystemPrompt.ROUTE)) {
                if (user.contains("mon essai")) return "type:\nOUTOFSCOPE\n\nclear:\nyes";
                if (user.contains("nécessaire")) return "type:\nREPLY\n\nclear:\nno";
                if (user.contains("Zoe")) return "type:\nSUMMARIZE\n\nclear:\nyes";
                return "type:\nREPLY\n\nclear:\nyes";
            }
            if (system.equals(PlannerSystemPrompt.ASK_USER)) return "Quelle réunion ?";
            if (system.equals(PlannerSystemPrompt.LOCATE)) return "email:\n1";
            if (system.equals(PlannerSystemPrompt.READ)) {
                return """
                        body:
                        Réunion vendredi à 10h.

                        kind:
                        INFORMATIVE
                        """;
            }
            if (system.equals(PlannerSystemPrompt.BRIEF)) {
                String fact = user.contains("correction:") ? "" : """
                        fact:
                        Apporter le contrat
                        """;
                return """
                        to:
                        ada@u.test

                        language:
                        FR

                        fact:
                        Réunion vendredi à 10h.
                        """ + fact;
            }
            if (system.equals(PlannerSystemPrompt.ARGUMENT)) return "argument:\naucun fait lu ne porte le contrat";
            if (system.equals(PlannerSystemPrompt.LESSON)) return "lesson:\nNe retiens un fait que s'il est dans un document lu.";
            if (system.equals(PlannerSystemPrompt.CHECKLIST)) {
                return """
                        part:
                        TO

                        expected:
                        ada@u.test

                        part:
                        FACT

                        expected:
                        Réunion vendredi à 10h.
                        """;
            }
            throw new AssertionError(system);
        };

        MailClientMcp mail = new MailClientMcp() {
            public List<Email> retrieveRelevantEmails(String query) {
                return query.contains("Zoe") ? List.of() : List.of(email);
            }
            public List<EmailThread> retrieveRelevantThreads(String query) { return List.of(); }
            public List<AttachmentFile> retrieveAttachments(Email ignored) { return List.of(); }
            public List<AttachmentFile> retrieveAttachments(EmailThread ignored) { return List.of(); }
        };

        LongTermMemory memory = seeded();
        int before = memory.getRules().size();
        Planner planner = new Planner(mail, model);
        Planner.Run result = planner.run(new Request("Réponds à Ada. Écris mon essai.", List.of()), memory,
                (text, brief) -> {
                    Dispute gap = new Dispute();
                    gap.setPart(BriefPart.FACT);
                    gap.setCriticArgument("Apporter le contrat");
                    return List.of(gap);
                },
                dispute -> true);

        assert result.questions.size() == 2;
        assert result.questions.get(0).getReason() == QuestionReason.UNCLEAR;
        assert result.questions.get(1).getReason() == QuestionReason.NO_MAIL;
        assert result.briefs.size() == 1;
        Brief brief = result.briefs.get(0);
        assert brief.getReplyToId().equals(1L);
        assert brief.getTo().equals(List.of("ada@u.test"));
        assert brief.getFacts().size() == 1;
        assert brief.getFacts().get(0).getBody().equals("Réunion vendredi à 10h.");
        assert brief.getLanguage() == com.assistant.mail.model.brief.Language.FR;
        assert brief.getFacts().get(0).getSourceEmailId().equals(1L);
        assert brief.getFacts().get(0).getSourceFile() == null;
        assert result.contexts.get(0).getEmailIds().equals(List.of(1L));
        assert result.checklists.get(0).getItems().stream().noneMatch(item -> "Apporter le contrat".equals(item.getExpected()));

        String locate = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.LOCATE)).findFirst().orElseThrow();
        String read = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.READ)).findFirst().orElseThrow();
        String briefCall = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.BRIEF)).findFirst().orElseThrow();
        String route = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.ROUTE) && call.contains("Ada")).findFirst().orElseThrow();
        String unclear = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.ASK_USER) && call.contains("nécessaire")).findFirst().orElseThrow();
        assert !locate.contains("SECRET_BODY");
        assert read.contains("SECRET_BODY");
        assert !briefCall.contains("SECRET_BODY");
        assert briefCall.contains("mail 1: Réunion vendredi à 10h.");
        assert !unclear.contains("reason:");
        assert unclear.contains("language: FR");
        String lesson = calls.stream().filter(call -> call.startsWith(PlannerSystemPrompt.LESSON)).findFirst().orElseThrow();
        assert lesson.contains("language: FR");
        assert Languages.explicit("Écris-lui in English") == com.assistant.mail.model.brief.Language.EN;
        assert Languages.explicit("Réponds en français") == com.assistant.mail.model.brief.Language.FR;
        assert Languages.output(com.assistant.mail.model.plan.InstructionType.COMPOSE, "Écris à Ada", com.assistant.mail.model.brief.Language.FR, "Please send the form") == com.assistant.mail.model.brief.Language.FR;
        assert Languages.output(com.assistant.mail.model.plan.InstructionType.REPLY, "Réponds", com.assistant.mail.model.brief.Language.FR, "Please send the form to the office") == com.assistant.mail.model.brief.Language.EN;
        String briefRules = briefCall.substring(briefCall.indexOf("\nrules:\n"));
        assert briefRules.lines().filter(line -> line.startsWith("- ")).count() == PlannerMemory.LIMIT;
        assert briefRules.contains("rule-11");
        assert !briefRules.contains("rule-0");
        assert !briefRules.contains("summarize-rule");
        assert route.substring(route.indexOf("\nrules:\n")).lines().filter(line -> line.startsWith("- ")).count() == PlannerMemory.LIMIT;
        assert route.contains("summarize-rule");
        assert !route.contains("vetoed-rule");

        assert memory.getRules().size() == before + 1;
        MemoryRule added = memory.getRules().get(memory.getRules().size() - 1);
        assert added.getOrigin() == MemoryOrigin.CRITIC;
        assert added.getAgent() == AgentKind.PLANNER;
        assert added.getText().equals("Ne retiens un fait que s'il est dans un document lu.");
        assert result.disputes.getDisputes().size() == 1;
        assert result.trace.getSteps().stream().anyMatch(step -> step.getActor() == TraceActor.PICKER && "true".equals(step.getDetail()));

        LongTermMemory kept = seeded();
        int keptBefore = kept.getRules().size();
        planner.run(new Request("Réponds à Ada. Écris mon essai.", List.of()), kept,
                (text, ignored) -> {
                    Dispute gap = new Dispute();
                    gap.setCriticArgument("Apporter le contrat");
                    return List.of(gap);
                },
                dispute -> false);
        assert kept.getRules().size() == keptBefore;
    }

    private static LongTermMemory seeded() {
        LongTermMemory memory = new LongTermMemory();
        List<MemoryRule> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) rules.add(rule("rule-" + i, AgentKind.PLANNER, InstructionType.REPLY, i, false));
        rules.add(rule("summarize-rule", AgentKind.PLANNER, InstructionType.SUMMARIZE, 99, false));
        rules.add(rule("vetoed-rule", AgentKind.PLANNER, InstructionType.REPLY, 100, true));
        rules.add(rule("drafter-rule", AgentKind.DRAFTER, InstructionType.REPLY, 100, false));
        memory.setRules(rules);
        return memory;
    }

    private static MemoryRule rule(String text, AgentKind agent, InstructionType task, int helpful, boolean vetoed) {
        MemoryRule rule = new MemoryRule();
        rule.setText(text);
        rule.setAgent(agent);
        rule.setTask(task);
        rule.setHelpful(helpful);
        rule.setVetoed(vetoed);
        return rule;
    }
}

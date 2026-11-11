package com.assistant.mail.workflow;

import com.assistant.mail.model.memory.AgentKind;
import com.assistant.mail.model.memory.Dispute;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.MemoryOrigin;
import com.assistant.mail.model.memory.MemoryRule;
import com.assistant.mail.model.plan.InstructionType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PlannerMemory {
    public static final int LIMIT = 10;

    private PlannerMemory() {}

    /** Planner rules for one task. A null task keeps every task, for the route call. */
    public static List<MemoryRule> forPlanner(LongTermMemory memory, InstructionType task) {
        if (memory == null || memory.getRules() == null) return List.of();
        return memory.getRules().stream()
                .filter(rule -> rule.getAgent() == AgentKind.PLANNER)
                .filter(rule -> !Boolean.TRUE.equals(rule.getVetoed()))
                .filter(rule -> task == null || rule.getTask() == null || rule.getTask() == task)
                .sorted(Comparator.comparingInt((MemoryRule rule) -> rule.getHelpful() - rule.getHarmful()).reversed())
                .limit(LIMIT)
                .toList();
    }

    /** A rule is written when the caller keeps a lesson. A veto is not calling this. */
    public static void remember(LongTermMemory memory, Dispute dispute) {
        if (memory == null || dispute == null) return;
        if (dispute.getLesson() == null || dispute.getLesson().isBlank()) return;
        MemoryRule rule = new MemoryRule();
        rule.setText(dispute.getLesson().trim());
        rule.setAgent(AgentKind.PLANNER);
        rule.setTask(dispute.getTask());
        rule.setPart(dispute.getPart());
        rule.setOrigin(MemoryOrigin.CRITIC);
        rule.setReason(dispute.getCriticArgument());
        memory.getRules().add(rule);
    }

    static String render(List<MemoryRule> rules) {
        StringBuilder text = new StringBuilder();
        for (MemoryRule rule : rules) text.append("- ").append(rule.getText()).append('\n');
        return text.toString();
    }

    public static void main(String[] args) {
        LongTermMemory memory = new LongTermMemory();
        List<MemoryRule> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) rules.add(rule("rule-" + i, AgentKind.PLANNER, InstructionType.REPLY, i, false));
        rules.add(rule("summarize-rule", AgentKind.PLANNER, InstructionType.SUMMARIZE, 99, false));
        rules.add(rule("vetoed-rule", AgentKind.PLANNER, InstructionType.REPLY, 100, true));
        rules.add(rule("drafter-rule", AgentKind.DRAFTER, InstructionType.REPLY, 100, false));
        memory.setRules(rules);

        List<MemoryRule> brief = forPlanner(memory, InstructionType.REPLY);
        assert brief.size() == LIMIT;
        assert brief.get(0).getText().equals("rule-11");
        assert brief.stream().noneMatch(rule -> rule.getText().equals("rule-0") || rule.getText().equals("rule-1"));
        assert brief.stream().noneMatch(rule -> rule.getText().equals("summarize-rule"));
        assert brief.stream().noneMatch(rule -> rule.getText().equals("vetoed-rule"));
        assert brief.stream().noneMatch(rule -> rule.getText().equals("drafter-rule"));

        List<MemoryRule> route = forPlanner(memory, null);
        assert route.size() == LIMIT;
        assert route.get(0).getText().equals("summarize-rule");

        int before = memory.getRules().size();
        Dispute blank = new Dispute();
        blank.setCriticRight(true);
        blank.setLesson(" ");
        remember(memory, blank);
        assert memory.getRules().size() == before;

        Dispute kept = new Dispute();
        kept.setCriticRight(false);
        kept.setLesson("keep the meeting fact");
        kept.setTask(InstructionType.REPLY);
        remember(memory, kept);
        assert memory.getRules().size() == before + 1;

        Dispute lost = new Dispute();
        lost.setCriticRight(true);
        lost.setLesson("copy only a fact that was read");
        lost.setTask(InstructionType.REPLY);
        lost.setCriticArgument("Apporter le contrat");
        remember(memory, lost);
        MemoryRule added = memory.getRules().get(memory.getRules().size() - 1);
        assert added.getText().equals("copy only a fact that was read");
        assert added.getAgent() == AgentKind.PLANNER;
        assert added.getOrigin() == MemoryOrigin.CRITIC;
        assert added.getTask() == InstructionType.REPLY;
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

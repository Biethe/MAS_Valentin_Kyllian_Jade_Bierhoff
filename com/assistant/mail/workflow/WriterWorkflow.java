package com.assistant.mail.workflow;

import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.brief.Checklist;
import com.assistant.mail.model.brief.ChecklistItem;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.memory.AgentKind;
import com.assistant.mail.model.memory.Dispute;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Writes one reply or compose draft from a planner brief and checklist, then saves it once. Never sees the user's request. */
public final class WriterWorkflow {
    /** @return true when the user vetoes the lesson. */
    public interface LessonVeto {
        boolean veto(String lesson);
    }

    private final DrafterAgentClient model;
    private final MailClientMcp mail;
    private final LongTermMemory memory;
    private final LessonVeto veto;

    public WriterWorkflow(DrafterAgentClient model, MailClientMcp mail, LongTermMemory memory, LessonVeto veto) {
        this.model = model;
        this.mail = mail;
        this.memory = memory;
        this.veto = veto;
    }

    public Result run(Brief brief, Checklist checklist) {
        String raw = call(DrafterSystemPrompt.TEXT, writePrompt(brief, checklist)).trim();
        mark(checklist, raw);
        List<Dispute> disputes = new ArrayList<>();
        StringBuilder corrections = new StringBuilder();
        debate(brief, checklist, raw, disputes, corrections);
        if (corrections.length() > 0) {
            raw = call(DrafterSystemPrompt.REWRITE, rewritePrompt(brief, checklist, raw, corrections)).trim();
            clear(checklist);
            mark(checklist, raw);
        }
        Draft draft = draft(brief, checklist, raw);
        String why = ToolGate.deny(ToolGate.draft(draft.getRecipients(), draft.getCc(), draft.getBcc(), draft.getSubject(), draft.getBody()),
                Set.of(), Set.of(), brief, false);
        if (why != null) {
            raw = call(DrafterSystemPrompt.REWRITE, rewritePrompt(brief, checklist, raw, new StringBuilder()) + "rejected:\n" + why).trim();
            clear(checklist);
            mark(checklist, raw);
            draft = draft(brief, checklist, raw);
            why = ToolGate.deny(ToolGate.draft(draft.getRecipients(), draft.getCc(), draft.getBcc(), draft.getSubject(), draft.getBody()),
                    Set.of(), Set.of(), brief, false);
            if (why != null) return new Result(null, disputes, why);
        }
        draft.setId(mail.createDraft(draft.getRecipients(), draft.getCc(), draft.getBcc(),
                draft.getSubject(), draft.getBody(), brief.getReplyToId()));
        return new Result(draft, disputes, null);
    }

    private void debate(Brief brief, Checklist checklist, String raw, List<Dispute> disputes, StringBuilder corrections) {
        for (Map<String, String> gap : blocks(call(DrafterSystemPrompt.CRITIC, criticPrompt(brief, raw, checklist)))) {
            BriefPart part = enumOrNull(BriefPart.class, gap.get("part"));
            String argument = text(gap.get("argument")).strip();
            String expected = text(gap.get("expected")).strip();
            if (part == null || argument.isEmpty()) continue;
            Dispute dispute = new Dispute();
            dispute.setAgent(AgentKind.DRAFTER);
            dispute.setTask(brief.getTask());
            dispute.setPart(part);
            dispute.setCriticArgument(argument);
            dispute.setCorrection(text(gap.get("instead")).strip());
            dispute.setAgentArgument(field(call(DrafterSystemPrompt.ARGUMENT, argumentPrompt(brief, raw, part, expected, argument)), "argument"));
            boolean criticWins = "critic".equalsIgnoreCase(field(call(DrafterSystemPrompt.JUDGE, judgePrompt(brief, raw, part, expected, dispute)), "winner"));
            dispute.setCriticRight(criticWins);
            dispute.setLesson(field(call(DrafterSystemPrompt.LESSON, lessonPrompt(brief, part, expected, dispute)), "lesson"));
            boolean vetoed = veto != null && veto.veto(dispute.getLesson());
            PlannerMemory.remember(memory, dispute, vetoed);
            disputes.add(dispute);
            if (criticWins) {
                corrections.append("part: ").append(part).append('\n');
                corrections.append("expected: ").append(expected).append('\n');
                corrections.append("instead: ").append(dispute.getCorrection()).append('\n');
            }
        }
    }

    private static Draft draft(Brief brief, Checklist checklist, String raw) {
        Draft draft = new Draft();
        draft.setRecipients(linesOf(field(raw, "recipients")));
        draft.setCc(linesOf(field(raw, "cc")));
        draft.setBcc(linesOf(field(raw, "bcc")));
        draft.setSubject(field(raw, "subject"));
        draft.setBody(field(raw, "body"));
        draft.setTone(brief.getTone());
        draft.setStructure(brief.getStructure());
        draft.setInformation(covered(brief, checklist));
        draft.setAttachments(brief.getAttachments());
        return draft;
    }

    private void mark(Checklist checklist, String raw) {
        List<ChecklistItem> items = checklist.getItems();
        boolean[] used = new boolean[items.size()];
        for (Map<String, String> block : blocks(call(DrafterSystemPrompt.MARK, markPrompt(raw, checklist)))) {
            BriefPart part = enumOrNull(BriefPart.class, block.get("part"));
            String expected = block.getOrDefault("expected", "").strip();
            if (part == null) continue;
            for (int i = 0; i < items.size(); i++) {
                ChecklistItem item = items.get(i);
                if (used[i] || item.getPart() != part || !expected.equals(text(item.getExpected()))) continue;
                item.setChecked(yes(block.get("checked")));
                item.setFeedback(block.getOrDefault("feedback", ""));
                used[i] = true;
                break;
            }
        }
    }

    private String writePrompt(Brief brief, Checklist checklist) {
        return briefText(brief) + "checklist:\n" + lines(checklist) + "rules:\n"
                + PlannerMemory.render(PlannerMemory.forDrafter(memory, brief.getTask()));
    }

    private static String criticPrompt(Brief brief, String raw, Checklist checklist) {
        return briefText(brief) + "draft:\n" + raw + "\n\nchecklist:\n" + marked(checklist);
    }

    private static String argumentPrompt(Brief brief, String raw, BriefPart part, String expected, String critic) {
        return briefText(brief) + "draft:\n" + raw
                + "\n\npart: " + part + "\nexpected: " + expected + "\ncritic:\n" + critic;
    }

    private static String judgePrompt(Brief brief, String raw, BriefPart part, String expected, Dispute dispute) {
        return briefText(brief) + "draft:\n" + raw
                + "\n\npart: " + part + "\nexpected: " + expected
                + "\ncritic:\n" + dispute.getCriticArgument()
                + "\nwriter:\n" + dispute.getAgentArgument();
    }

    private static String lessonPrompt(Brief brief, BriefPart part, String expected, Dispute dispute) {
        String winner = Boolean.TRUE.equals(dispute.getCriticRight()) ? "critic" : "writer";
        return "language: " + (brief.getLanguage() == null ? "" : brief.getLanguage().name())
                + "\nwinner: " + winner
                + "\npart: " + part
                + "\nexpected: " + expected
                + "\ncritic:\n" + dispute.getCriticArgument()
                + "\nwriter:\n" + dispute.getAgentArgument();
    }

    private String rewritePrompt(Brief brief, Checklist checklist, String raw, StringBuilder corrections) {
        return writePrompt(brief, checklist) + "draft:\n" + raw + "\n\ncorrections:\n" + corrections;
    }

    private static String markPrompt(String raw, Checklist checklist) {
        return "draft:\n" + raw + "\n\nchecklist:\n" + lines(checklist);
    }

    static String briefText(Brief brief) {
        StringBuilder text = new StringBuilder();
        text.append("task: ").append(brief.getTask() == null ? "" : brief.getTask().name()).append('\n');
        text.append("language: ").append(brief.getLanguage() == null ? "" : brief.getLanguage().name()).append('\n');
        text.append("tone: ").append(brief.getTone() == null ? "" : brief.getTone().name()).append('\n');
        text.append("structure: ").append(brief.getStructure() == null ? "" : brief.getStructure().name()).append('\n');
        for (String address : brief.getTo()) text.append("to: ").append(address).append('\n');
        for (String address : brief.getCc()) text.append("cc: ").append(address).append('\n');
        for (String address : brief.getBcc()) text.append("bcc: ").append(address).append('\n');
        if (brief.getReplyToId() != null) text.append("replyTo: ").append(brief.getReplyToId()).append('\n');
        for (String note : brief.getStyleNotes()) text.append("styleNote: ").append(note).append('\n');
        for (Information fact : brief.getFacts()) text.append("fact: ").append(text(fact.getBody())).append('\n');
        for (Email email : brief.getEmails()) {
            if (email.getId() != null) text.append("email: ").append(email.getId()).append('\n');
        }
        for (AttachmentFile file : brief.getAttachments()) text.append("attachment: ").append(text(file.getName())).append('\n');
        for (String line : brief.getDoNotFollow()) text.append("doNotFollow: ").append(line).append('\n');
        return text.toString();
    }

    private static String lines(Checklist checklist) {
        StringBuilder text = new StringBuilder();
        for (ChecklistItem item : checklist.getItems()) {
            text.append("part: ").append(item.getPart() == null ? "" : item.getPart().name()).append('\n');
            text.append("expected: ").append(text(item.getExpected())).append('\n');
        }
        return text.toString();
    }

    private static String marked(Checklist checklist) {
        StringBuilder text = new StringBuilder();
        for (ChecklistItem item : checklist.getItems()) {
            text.append("part: ").append(item.getPart() == null ? "" : item.getPart().name()).append('\n');
            text.append("expected: ").append(text(item.getExpected())).append('\n');
            text.append("checked: ").append(item.isChecked() ? "yes" : "no").append('\n');
            text.append("feedback: ").append(text(item.getFeedback())).append('\n');
        }
        return text.toString();
    }

    private static void clear(Checklist checklist) {
        for (ChecklistItem item : checklist.getItems()) {
            item.setChecked(false);
            item.setFeedback("");
        }
    }

    private static List<Information> covered(Brief brief, Checklist checklist) {
        List<Information> facts = new ArrayList<>();
        for (Information fact : brief.getFacts()) {
            for (ChecklistItem item : checklist.getItems()) {
                if (item.getPart() == BriefPart.FACT && item.isChecked() && text(fact.getBody()).equals(text(item.getExpected()))) {
                    facts.add(fact);
                    break;
                }
            }
        }
        return facts;
    }

    private static List<String> linesOf(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (!line.isBlank()) lines.add(line.strip());
        }
        return lines;
    }

    private String call(String system, String user) {
        String raw = model.complete(system, user);
        return raw == null ? "" : raw;
    }

    static List<Map<String, String>> blocks(String raw) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> current = null;
        String key = null;
        StringBuilder value = new StringBuilder();
        if (raw == null) return out;
        for (String line : raw.split("\n", -1)) {
            if (line.matches("[A-Za-z][A-Za-z0-9]*:")) {
                if (key != null) current.put(key, value.toString().trim());
                String next = line.substring(0, line.length() - 1);
                if (current != null && current.containsKey(next)) {
                    out.add(current);
                    current = null;
                }
                if (current == null) current = new LinkedHashMap<>();
                key = next;
                value = new StringBuilder();
            } else if (key != null) {
                if (value.length() > 0) value.append('\n');
                value.append(line);
            }
        }
        if (key != null && current != null) current.put(key, value.toString().trim());
        if (current != null && !current.isEmpty()) out.add(current);
        return out;
    }

    private static String field(String raw, String key) {
        for (Map<String, String> block : blocks(raw)) {
            String value = block.get(key);
            if (value != null && !value.isBlank()) return value.strip();
        }
        return "";
    }

    private static boolean yes(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase("yes");
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static final class Result {
        private final Draft draft;
        private final List<Dispute> disputes;
        private final String blocked;

        Result(Draft draft, List<Dispute> disputes, String blocked) {
            this.draft = draft;
            this.disputes = disputes;
            this.blocked = blocked;
        }

        public Draft getDraft() { return draft; }
        public List<Dispute> getDisputes() { return disputes; }
        public String getBlocked() { return blocked; }
    }
}

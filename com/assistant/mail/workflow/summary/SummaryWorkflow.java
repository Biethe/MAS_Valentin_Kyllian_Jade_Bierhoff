package com.assistant.mail.workflow.summary;

import com.assistant.mail.workflow.planner.PlannerMemory;

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
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.service.Progress;
import com.assistant.mail.service.agent.SummarizerAgentClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes one summary from a planner brief and checklist, then one critic pass. Never sees the user's request. */
public final class SummaryWorkflow {
    /** @return true when the user vetoes the lesson. */
    public interface LessonVeto {
        boolean veto(String lesson);
    }

    private final SummarizerAgentClient model;
    private final LongTermMemory memory;
    private final LessonVeto veto;

    public SummaryWorkflow(SummarizerAgentClient model, LongTermMemory memory, LessonVeto veto) {
        this.model = model;
        this.memory = memory;
        this.veto = veto;
    }

    public Result run(Brief brief, Checklist checklist) {
        Progress.note("Writing the summary");
        String body = call(SummarizerSystemPrompt.TEXT, writePrompt(brief, checklist)).trim();
        mark(checklist, body);
        List<Dispute> disputes = new ArrayList<>();
        StringBuilder corrections = new StringBuilder();
        debate(brief, checklist, body, disputes, corrections);
        if (corrections.length() > 0) {
            body = call(SummarizerSystemPrompt.REWRITE, rewritePrompt(brief, checklist, body, corrections)).trim();
            clear(checklist);
            mark(checklist, body);
        }
        Summary summary = new Summary();
        summary.setBody(body);
        summary.setInformation(covered(brief, checklist));
        summary.setReferences(references(brief));
        return new Result(summary, disputes);
    }

    private void debate(Brief brief, Checklist checklist, String body, List<Dispute> disputes, StringBuilder corrections) {
        for (Map<String, String> gap : blocks(call(SummarizerSystemPrompt.CRITIC, criticPrompt(brief, body, checklist)))) {
            BriefPart part = enumOrNull(BriefPart.class, gap.get("part"));
            String argument = text(gap.get("argument")).strip();
            String expected = text(gap.get("expected")).strip();
            if (part == null || argument.isEmpty()) continue;
            Dispute dispute = new Dispute();
            dispute.setAgent(AgentKind.SUMMARIZER);
            dispute.setTask(brief.getTask());
            dispute.setPart(part);
            dispute.setCriticArgument(argument);
            dispute.setCorrection(text(gap.get("instead")).strip());
            dispute.setAgentArgument(field(call(SummarizerSystemPrompt.ARGUMENT, argumentPrompt(brief, body, part, expected, argument)), "argument"));
            boolean criticWins = "critic".equalsIgnoreCase(field(call(SummarizerSystemPrompt.JUDGE, judgePrompt(brief, body, part, expected, dispute)), "winner"));
            dispute.setCriticRight(criticWins);
            dispute.setLesson(field(call(SummarizerSystemPrompt.LESSON, lessonPrompt(brief, part, expected, dispute)), "lesson"));
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

    private void mark(Checklist checklist, String body) {
        List<ChecklistItem> items = checklist.getItems();
        boolean[] used = new boolean[items.size()];
        for (Map<String, String> block : blocks(call(SummarizerSystemPrompt.MARK, markPrompt(body, checklist)))) {
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
                + PlannerMemory.render(PlannerMemory.forSummarizer(memory, brief.getTask()));
    }

    private static String criticPrompt(Brief brief, String body, Checklist checklist) {
        return briefText(brief) + "summary:\n" + body + "\n\nchecklist:\n" + marked(checklist);
    }

    private static String argumentPrompt(Brief brief, String body, BriefPart part, String expected, String critic) {
        return briefText(brief) + "summary:\n" + body
                + "\n\npart: " + part + "\nexpected: " + expected + "\ncritic:\n" + critic;
    }

    private static String judgePrompt(Brief brief, String body, BriefPart part, String expected, Dispute dispute) {
        return briefText(brief) + "summary:\n" + body
                + "\n\npart: " + part + "\nexpected: " + expected
                + "\ncritic:\n" + dispute.getCriticArgument()
                + "\nsummarizer:\n" + dispute.getAgentArgument();
    }

    private static String lessonPrompt(Brief brief, BriefPart part, String expected, Dispute dispute) {
        String winner = Boolean.TRUE.equals(dispute.getCriticRight()) ? "critic" : "summarizer";
        return "language: " + (brief.getLanguage() == null ? "" : brief.getLanguage().name())
                + "\nwinner: " + winner
                + "\npart: " + part
                + "\nexpected: " + expected
                + "\ncritic:\n" + dispute.getCriticArgument()
                + "\nsummarizer:\n" + dispute.getAgentArgument();
    }

    private String rewritePrompt(Brief brief, Checklist checklist, String body, StringBuilder corrections) {
        return writePrompt(brief, checklist) + "summary:\n" + body + "\n\ncorrections:\n" + corrections;
    }

    private static String markPrompt(String body, Checklist checklist) {
        return "summary:\n" + body + "\n\nchecklist:\n" + lines(checklist);
    }

    static String briefText(Brief brief) {
        StringBuilder text = new StringBuilder();
        text.append("task: ").append(brief.getTask() == null ? "" : brief.getTask().name()).append('\n');
        text.append("language: ").append(brief.getLanguage() == null ? "" : brief.getLanguage().name()).append('\n');
        text.append("tone: ").append(brief.getTone() == null ? "" : brief.getTone().name()).append('\n');
        text.append("structure: ").append(brief.getStructure() == null ? "" : brief.getStructure().name()).append('\n');
        for (String note : brief.getStyleNotes()) text.append("styleNote: ").append(note).append('\n');
        for (Information fact : brief.getFacts()) text.append("fact: ").append(text(fact.getBody())).append('\n');
        for (Email email : brief.getEmails()) {
            text.append("mail:\n");
            text.append("subject: ").append(text(email.getSubject())).append('\n');
            if (email.getReceived() != null && !email.getReceived().isBlank()) {
                text.append("received: ").append(text(email.getReceived())).append('\n');
            }
            if (email.getSender() != null) text.append("from: ").append(text(email.getSender().getEmail())).append('\n');
            text.append(text(email.getBody())).append('\n');
            for (AttachmentFile file : email.getAttachments()) {
                text.append("file: ").append(text(file.getName())).append('\n');
                if (file.getData() != null && file.getData().length > 0) {
                    text.append(new String(file.getData(), java.nio.charset.StandardCharsets.UTF_8)).append('\n');
                }
            }
            text.append('\n');
        }
        for (AttachmentFile file : brief.getContextFiles()) {
            text.append("file: ").append(text(file.getName())).append('\n');
            if (file.getData() != null && file.getData().length > 0) {
                text.append(new String(file.getData(), java.nio.charset.StandardCharsets.UTF_8)).append("\n\n");
            }
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

    private static List<String> references(Brief brief) {
        List<String> refs = new ArrayList<>();
        for (Email email : brief.getEmails()) {
            if (email.getId() != null) refs.add(email.getId().toString());
        }
        for (AttachmentFile file : brief.getAttachments()) addName(refs, file);
        for (AttachmentFile file : brief.getContextFiles()) addName(refs, file);
        return refs;
    }

    private static void addName(List<String> refs, AttachmentFile file) {
        if (file.getName() != null && !file.getName().isBlank()) refs.add(file.getName());
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
        private final Summary summary;
        private final List<Dispute> disputes;

        Result(Summary summary, List<Dispute> disputes) {
            this.summary = summary;
            this.disputes = disputes;
        }

        public Summary getSummary() { return summary; }
        public List<Dispute> getDisputes() { return disputes; }
    }
}

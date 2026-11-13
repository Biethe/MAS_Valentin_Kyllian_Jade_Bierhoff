package com.assistant.mail.workflow.writer;

import com.assistant.mail.workflow.planner.PlannerMemory;
import com.assistant.mail.workflow.shared.ToolGate;

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
import com.assistant.mail.service.Progress;
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
        Progress.note("Writing the draft");
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
        draft.setLink(mail.draftLink(draft.getId()));
        return new Result(draft, disputes, null);
    }

    private void debate(Brief brief, Checklist checklist, String raw, List<Dispute> disputes, StringBuilder corrections) {
        if (System.getenv("MAIL_CRITIC") == null) return; // off by default: 3 extra model calls per dispute
        for (Map<String, String> gap : blocks(call(DrafterSystemPrompt.CRITIC, criticPrompt(brief, raw, checklist)))) {
            if (disputes.size() == 2) break; // ponytail: 2 disputes per draft caps latency; raise if lessons run dry
            BriefPart part = enumOrNull(BriefPart.class, gap.get("part"));
            String argument = text(gap.get("argument")).strip();
            String expected = text(gap.get("expected")).strip();
            if (part == null || argument.isEmpty() || agrees(argument)) continue;
            Dispute dispute = new Dispute();
            dispute.setAgent(AgentKind.DRAFTER);
            dispute.setTask(brief.getTask());
            dispute.setPart(part);
            dispute.setCriticArgument(argument);
            dispute.setCorrection(text(gap.get("instead")).strip());
            dispute.setAgentArgument(answer(call(DrafterSystemPrompt.ARGUMENT, argumentPrompt(brief, raw, part, expected, argument)), "argument"));
            boolean criticWins = answer(call(DrafterSystemPrompt.JUDGE, judgePrompt(brief, raw, part, expected, dispute)), "winner").toLowerCase().startsWith("critic");
            dispute.setCriticRight(criticWins);
            dispute.setLesson(answer(call(DrafterSystemPrompt.LESSON, lessonPrompt(brief, part, expected, dispute)), "lesson"));
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
        List<String> recipients = new ArrayList<>();
        if (brief.getTo() != null) {
            for (String line : brief.getTo()) if (line != null && line.contains("@")) recipients.add(line);
        }
        if (recipients.isEmpty() && brief.getFacts() != null) {
            StringBuilder blob = new StringBuilder();
            for (com.assistant.mail.model.fact.Information fact : brief.getFacts()) blob.append(fact.getBody()).append('\n');
            String email = com.assistant.mail.workflow.planner.Planner.directory(blob.toString());
            if (email != null) recipients.add(email);
        }
        draft.setRecipients(recipients);
        List<String> cc = new ArrayList<>(); // the brief decides who is copied, never text the model saw in a mail
        if (brief.getCc() != null) {
            for (String line : brief.getCc()) if (line != null && line.contains("@")) cc.add(line);
        }
        draft.setCc(cc);
        draft.setBcc(List.of());
        String subject = field(raw, "subject");
        draft.setSubject(subject.isBlank() ? "Message" : subject);
        String body = field(raw, "body");
        if (body == null) body = "";
        if (!body.matches("(?is)^\\s*(bonjour|bonsoir|salut|hello|hi|dear|cher|chère|madame|monsieur)\\b.*")) {
            body = (brief.getLanguage() == com.assistant.mail.model.brief.Language.FR ? "Bonjour,\n\n" : "Hello,\n\n") + body;
        }
        draft.setBody(body);
        draft.setTone(brief.getTone());
        draft.setStructure(brief.getStructure());
        draft.setInformation(covered(brief, checklist));
        draft.setAttachments(brief.getAttachments());
        return draft;
    }

    private void mark(Checklist checklist, String raw) {
        List<ChecklistItem> items = checklist.getItems();
        if (items == null || items.isEmpty()) return;
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

    /** A friend, or an original mail that opens like one. */
    static boolean informal(Brief brief) {
        if (brief.getRecipientRole() != null && brief.getRecipientRole().toLowerCase(java.util.Locale.ROOT).contains("friend")) return true;
        for (Email email : brief.getEmails()) {
            if (text(email.getBody()).matches("(?is)^(salut|coucou|hey|hi)\\b.*")) return true;
        }
        return false;
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
        if (informal(brief)) {
            text.append(brief.getLanguage() == com.assistant.mail.model.brief.Language.FR
                    ? "register: informal. The recipient is a friend: tutoie (tu, te, ton), greet with Salut, close with À plus. Never vous.\n"
                    : "register: informal. The recipient is a friend: greet with Hi, close with Cheers or See you. Warm and casual.\n");
        }
        for (Information fact : brief.getFacts()) {
            if (fact.getKind() == com.assistant.mail.model.fact.InformationKind.SUSPICIOUS_INSTRUCTION) continue;
            text.append("fact: ").append(text(fact.getBody())).append('\n');
        }
        for (Email email : brief.getEmails()) {
            text.append("original mail:\n");
            text.append("subject: ").append(text(email.getSubject())).append('\n');
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

    /** "key:" alone on a line, or one of the block keys with its value on the same line ("part: TONE"). */
    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile(
            "([A-Za-z][A-Za-z0-9]*):\\s*|\\s*(part|expected|argument|instead|checked|feedback):[ \\t]*(.*)");

    public static List<Map<String, String>> blocks(String raw) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> current = null;
        String key = null;
        StringBuilder value = new StringBuilder();
        if (raw == null) return out;
        for (String line : raw.split("\n", -1)) {
            java.util.regex.Matcher m = KEY.matcher(line);
            if (m.matches()) {
                if (key != null) current.put(key, value.toString().trim());
                String next = m.group(1) != null ? m.group(1) : m.group(2);
                if (current != null && current.containsKey(next)) {
                    out.add(current);
                    current = null;
                }
                if (current == null) current = new LinkedHashMap<>();
                key = next;
                value = new StringBuilder(m.group(1) != null ? "" : m.group(3));
            } else if (key != null) {
                if (value.length() > 0) value.append('\n');
                value.append(line);
            }
        }
        if (key != null && current != null) current.put(key, value.toString().trim());
        if (current != null && !current.isEmpty()) out.add(current);
        return out;
    }

    /** A one-field answer: the labeled value, or the whole answer when the model dropped the label. */
    public static String answer(String raw, String key) {
        String v = field(raw, key);
        return v.isEmpty() && raw != null ? raw.strip().replaceFirst("(?i)^" + key + ":\\s*", "") : v;
    }

    /** True when a critic block only agrees ("The tone is correct."); small models list every part. */
    public static boolean agrees(String argument) {
        return argument.matches("(?is).*\\b(is|are|seems|looks) (correct|fine|ok|appropriate|accurate)\\b.*|.*which is correct.*");
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

    /** Self-check: critic blocks parse with values on the key line or below it. */
    public static void main(String[] args) {
        List<Map<String, String>> b = blocks("part: TONE\nexpected: formal\nargument: too casual\n\npart:\nFACT\nargument:\nmissing date\ninstead: add 20 Nov");
        assert b.size() == 2 : b;
        assert b.get(0).get("part").equals("TONE") && b.get(0).get("argument").equals("too casual") : b;
        assert answer("Keep dates.", "lesson").equals("Keep dates.") && answer("lesson:\nKeep dates.", "lesson").equals("Keep dates.");
        assert agrees("The tone is correct.") && !agrees("The date is missing.");
        assert b.get(1).get("part").equals("FACT") && b.get(1).get("instead").equals("add 20 Nov") : b;
        System.out.println("WriterWorkflow ok");
    }
}

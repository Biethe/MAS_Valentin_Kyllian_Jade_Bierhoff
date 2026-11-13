package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.ask.QuestionReason;
import com.assistant.mail.model.ask.Request;
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
import com.assistant.mail.model.memory.Dispute;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.memory.ShortTermMemory;
import com.assistant.mail.model.plan.Context;
import com.assistant.mail.model.plan.Instruction;
import com.assistant.mail.model.plan.InstructionType;
import com.assistant.mail.model.trace.Trace;
import com.assistant.mail.model.trace.TraceActor;
import com.assistant.mail.model.trace.TraceStep;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class PlannerWorkflow {
    private final MailClientMcp mail;
    private final PlannerAgentClient model;

    public PlannerWorkflow(MailClientMcp mail, PlannerAgentClient model) {
        this.mail = mail;
        this.model = model;
    }

    public PlannerResult run(Request request, LongTermMemory memory) {
        String query = request == null || request.getText() == null ? "" : request.getText();
        List<AttachmentFile> userFiles = request == null || request.getFiles() == null ? List.of() : request.getFiles();
        PlannerResult result = new PlannerResult(query);
        for (String body : blocks(call(PlannerSystemPrompt.SPLIT, "query:\n" + query)).getOrDefault("body", List.of())) {
            Instruction instruction = route(body, memory);
            if (instruction.getType() == InstructionType.OUTOFSCOPE) {
                result.refused.add(instruction);
                continue;
            }
            if (instruction.getType() == null || !instruction.getIsClear()) {
                result.questions.add(ask(body, QuestionReason.UNCLEAR));
                continue;
            }
            List<Email> emails = mail.retrieveRelevantEmails(body);
            List<EmailThread> threads = mail.retrieveRelevantThreads(body);
            if (emails == null) emails = List.of();
            if (threads == null) threads = List.of();
            Context context = locate(body, emails, threads, userFiles);
            List<Doc> docs = resolve(context, emails, threads, userFiles);
            if (docs.isEmpty()) {
                result.questions.add(ask("gap: no matching mail\n" + body, QuestionReason.NO_MAIL));
                continue;
            }
            List<Information> facts = new ArrayList<>();
            List<String> readTexts = new ArrayList<>();
            for (Doc doc : docs) {
                readTexts.add(doc.text);
                facts.addAll(read(instruction, doc));
            }
            Brief brief = brief(instruction, facts, docs, memory);
            dispute(result, query, instruction, brief, facts, readTexts, memory);
            result.briefs.add(brief);
            result.checklists.add(checklist(brief));
            String hole = hole(brief);
            if (hole != null) result.questions.add(ask(body + "\ngap: " + hole, QuestionReason.MISSING_FACT));
        }
        return result;
    }

    private Instruction route(String body, LongTermMemory memory) {
        String user = "instruction:\n" + body + "\n\nrules:\n" + PlannerMemory.render(PlannerMemory.forPlanner(memory, null));
        Map<String, List<String>> parsed = blocks(call(PlannerSystemPrompt.ROUTE, user));
        Instruction instruction = new Instruction();
        instruction.setBody(body);
        instruction.setIsAtomic(true);
        instruction.setType(enumOrNull(InstructionType.class, first(parsed, "type")));
        instruction.setIsClear(yes(first(parsed, "clear")));
        return instruction;
    }

    private Question ask(String body, QuestionReason reason) {
        String text = call(PlannerSystemPrompt.ASK_USER, body).trim();
        return new Question(text, reason, List.of());
    }

    private Context locate(String body, List<Email> emails, List<EmailThread> threads, List<AttachmentFile> userFiles) {
        String user = "instruction:\n" + body + "\n\n" + index(emails, threads, userFiles);
        Map<String, List<String>> parsed = blocks(call(PlannerSystemPrompt.LOCATE, user));
        Context context = new Context();
        for (String id : parsed.getOrDefault("email", List.of())) {
            try {
                context.getEmailIds().add(Long.valueOf(id.trim()));
            } catch (NumberFormatException ignored) {
                // a hit that is not an id selects nothing
            }
        }
        context.getThreadIds().addAll(parsed.getOrDefault("thread", List.of()));
        context.getAttachedFiles().addAll(parsed.getOrDefault("attachment", List.of()));
        context.getFromUserFiles().addAll(parsed.getOrDefault("userFile", List.of()));
        return context;
    }

    private List<Information> read(Instruction instruction, Doc doc) {
        String user = "instruction:\n" + instruction.getBody() + "\n\ndocument:\n" + doc.text;
        List<Information> facts = new ArrayList<>();
        Map<String, List<String>> parsed = blocks(call(PlannerSystemPrompt.READ, user));
        List<String> bodies = parsed.getOrDefault("body", List.of());
        List<String> kinds = parsed.getOrDefault("kind", List.of());
        List<String> due = parsed.getOrDefault("dueTime", List.of());
        for (int i = 0; i < bodies.size(); i++) {
            InformationKind kind = enumOrNull(InformationKind.class, i < kinds.size() ? kinds.get(i) : null);
            if (kind == null) kind = InformationKind.INFORMATIVE;
            Instant dueTime = null;
            if (i < due.size() && !due.get(i).isBlank()) {
                try {
                    dueTime = Instant.parse(due.get(i).trim());
                } catch (Exception ignored) {
                    dueTime = null;
                }
            }
            facts.add(new Information(bodies.get(i), kind, dueTime));
        }
        return facts;
    }

    private Brief brief(Instruction instruction, List<Information> facts, List<Doc> docs, LongTermMemory memory) {
        StringBuilder user = new StringBuilder();
        user.append("instruction:\n").append(instruction.getBody()).append("\n\n");
        user.append("task:\n").append(instruction.getType()).append("\n\n");
        user.append("envelope:\n").append(envelope(docs)).append('\n');
        user.append("facts:\n");
        for (Information fact : facts) user.append("- ").append(fact.getBody()).append('\n');
        user.append("\nrules:\n").append(PlannerMemory.render(PlannerMemory.forPlanner(memory, instruction.getType())));
        Map<String, List<String>> parsed = blocks(call(PlannerSystemPrompt.BRIEF, user.toString()));
        Brief brief = new Brief();
        brief.setTask(instruction.getType());
        brief.setTo(parsed.getOrDefault("to", List.of()));
        brief.setCc(parsed.getOrDefault("cc", List.of()));
        brief.setBcc(parsed.getOrDefault("bcc", List.of()));
        brief.setLanguage(enumOrNull(Language.class, first(parsed, "language")));
        brief.setRecipientRole(first(parsed, "recipientRole"));
        brief.setTone(enumOrNull(DraftTone.class, first(parsed, "tone")));
        brief.setStructure(enumOrNull(DraftStructure.class, first(parsed, "structure")));
        brief.setStyleNotes(parsed.getOrDefault("styleNote", List.of()));
        brief.setDoNotFollow(parsed.getOrDefault("doNotFollow", List.of()));
        List<String> chosen = parsed.getOrDefault("fact", List.of());
        if (chosen.isEmpty()) {
            brief.setFacts(new ArrayList<>(facts));
        } else {
            List<Information> kept = new ArrayList<>();
            for (String line : chosen) kept.add(matchOrNew(facts, line));
            brief.setFacts(kept);
        }
        for (Doc doc : docs) {
            if (doc.email != null) {
                brief.getEmails().add(doc.email);
                if (instruction.getType() == InstructionType.REPLY && brief.getReplyToId() == null) {
                    brief.setReplyToId(doc.email.getId());
                }
            }
            if (doc.file != null) brief.getAttachments().add(doc.file);
        }
        return brief;
    }

    private void dispute(PlannerResult result, String query, Instruction instruction, Brief brief,
                         List<Information> facts, List<String> readTexts, LongTermMemory memory) {
        List<Information> unbacked = new ArrayList<>();
        for (Information fact : brief.getFacts()) {
            if (criticRight(fact.getBody(), readTexts)) unbacked.add(fact);
        }
        for (Information fact : unbacked) {
            Dispute dispute = new Dispute();
            dispute.setAgent(AgentKind.PLANNER);
            dispute.setTask(instruction.getType());
            dispute.setPart(BriefPart.FACT);
            dispute.setCriticArgument(fact.getBody());
            dispute.setAgentArgument(argument(query, brief, facts, fact.getBody()));
            dispute.setCriticRight(criticRight(fact.getBody(), readTexts));
            if (Boolean.TRUE.equals(dispute.getCriticRight())) {
                dispute.setLesson(lesson(fact.getBody(), dispute.getAgentArgument()));
                PlannerMemory.remember(memory, dispute);
                brief.getFacts().removeIf(item -> fact.getBody().equals(item.getBody()));
            }
            result.shortTerm.getDisputes().add(dispute);
            TraceStep step = new TraceStep();
            step.setAt(Instant.now());
            step.setActor(TraceActor.PICKER);
            step.setAction("criticRight");
            step.setDetail(String.valueOf(dispute.getCriticRight()));
            result.trace.getSteps().add(step);
        }
    }

    private String argument(String query, Brief brief, List<Information> facts, String gap) {
        StringBuilder user = new StringBuilder();
        user.append("request:\n").append(query).append("\n\n");
        user.append("gap:\n").append(gap).append("\n\n");
        user.append("brief:\n");
        user.append("to: ").append(brief.getTo()).append('\n');
        for (Information fact : brief.getFacts()) user.append("fact: ").append(fact.getBody()).append('\n');
        user.append("\nfacts:\n");
        for (Information fact : facts) user.append("- ").append(fact.getBody()).append('\n');
        return first(blocks(call(PlannerSystemPrompt.ARGUMENT, user.toString())), "argument");
    }

    private String lesson(String gap, String argument) {
        String user = "gap:\n" + gap + "\n\nargument:\n" + argument;
        String line = first(blocks(call(PlannerSystemPrompt.LESSON, user)), "lesson");
        return line == null ? "" : line;
    }

    private String call(String system, String user) {
        String raw = model.complete(system, user);
        return raw == null ? "" : raw;
    }

    static boolean criticRight(String factBody, List<String> readTexts) {
        if (factBody == null || factBody.isBlank()) return true;
        String needle = factBody.toLowerCase();
        for (String text : readTexts) {
            if (text != null && text.toLowerCase().contains(needle)) return false;
        }
        return true;
    }

    static String hole(Brief brief) {
        if (brief.getTask() == InstructionType.REPLY && brief.getTo().isEmpty()) return "to";
        if (brief.getTask() == InstructionType.REPLY && brief.getReplyToId() == null) return "replyTo";
        if (brief.getTask() == InstructionType.COMPOSE && brief.getTo().isEmpty()) return "to";
        if (brief.getTask() == InstructionType.SUMMARIZE && brief.getFacts().isEmpty()) return "fact";
        return null;
    }

    static Checklist checklist(Brief brief) {
        Checklist checklist = new Checklist();
        add(checklist, BriefPart.TASK, brief.getTask() == null ? null : brief.getTask().name());
        for (String value : brief.getTo()) add(checklist, BriefPart.TO, value);
        for (String value : brief.getCc()) add(checklist, BriefPart.CC, value);
        for (String value : brief.getBcc()) add(checklist, BriefPart.BCC, value);
        add(checklist, BriefPart.LANGUAGE, brief.getLanguage() == null ? null : brief.getLanguage().name());
        add(checklist, BriefPart.RECIPIENT_ROLE, brief.getRecipientRole());
        add(checklist, BriefPart.TONE, brief.getTone() == null ? null : brief.getTone().name());
        add(checklist, BriefPart.STRUCTURE, brief.getStructure() == null ? null : brief.getStructure().name());
        for (String value : brief.getStyleNotes()) add(checklist, BriefPart.STYLE_NOTE, value);
        for (Information fact : brief.getFacts()) add(checklist, BriefPart.FACT, fact.getBody());
        for (AttachmentFile file : brief.getAttachments()) add(checklist, BriefPart.ATTACHMENT, file.getName());
        for (Email email : brief.getEmails()) add(checklist, BriefPart.EMAIL, email.getId() == null ? null : email.getId().toString());
        add(checklist, BriefPart.REPLY_TO, brief.getReplyToId() == null ? null : brief.getReplyToId().toString());
        for (String value : brief.getDoNotFollow()) add(checklist, BriefPart.DO_NOT_FOLLOW, value);
        return checklist;
    }

    private static void add(Checklist checklist, BriefPart part, String expected) {
        if (expected == null || expected.isBlank()) return;
        ChecklistItem item = new ChecklistItem();
        item.setPart(part);
        item.setExpected(expected);
        checklist.getItems().add(item);
    }

    static String index(List<Email> emails, List<EmailThread> threads, List<AttachmentFile> userFiles) {
        StringBuilder text = new StringBuilder();
        for (Email email : emails) text.append(hit(email));
        for (EmailThread thread : threads) {
            text.append("thread: ").append(thread.getThreadId()).append('\n');
            if (thread.getEmails() != null) {
                for (Email email : thread.getEmails()) text.append(hit(email));
            }
        }
        for (AttachmentFile file : userFiles) text.append("userFile: ").append(file.getName()).append('\n');
        return text.toString();
    }

    private static String hit(Email email) {
        StringBuilder text = new StringBuilder();
        text.append("email: ").append(email.getId()).append('\n');
        if (email.getSender() != null) text.append("from: ").append(email.getSender().getFullName()).append('\n');
        text.append("subject: ").append(email.getSubject() == null ? "" : email.getSubject()).append('\n');
        text.append("files:");
        if (email.getAttachments() != null) {
            for (AttachmentFile file : email.getAttachments()) text.append(' ').append(file.getName());
        }
        text.append("\nsnippet: ").append(snippet(email.getBody())).append('\n');
        return text.toString();
    }

    static String snippet(String body) {
        if (body == null || body.isBlank()) return "";
        String line = body.lines().findFirst().orElse("");
        return line.length() > 80 ? line.substring(0, 80) : line;
    }

    private static List<Doc> resolve(Context context, List<Email> emails, List<EmailThread> threads, List<AttachmentFile> userFiles) {
        List<Email> pool = new ArrayList<>(emails);
        for (EmailThread thread : threads) {
            if (thread.getEmails() != null) pool.addAll(thread.getEmails());
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(context.getEmailIds());
        for (String threadId : context.getThreadIds()) {
            for (EmailThread thread : threads) {
                if (!threadId.equals(thread.getThreadId()) || thread.getEmails() == null) continue;
                for (Email email : thread.getEmails()) {
                    if (email.getId() != null) ids.add(email.getId());
                }
            }
        }
        List<Doc> docs = new ArrayList<>();
        for (Long id : ids) {
            for (Email email : pool) {
                if (id.equals(email.getId())) {
                    docs.add(new Doc(email, null, email.getBody() == null ? "" : email.getBody()));
                    break;
                }
            }
        }
        for (String name : context.getAttachedFiles()) {
            for (Email email : pool) {
                if (email.getAttachments() == null) continue;
                for (AttachmentFile file : email.getAttachments()) {
                    if (name.equals(file.getName())) docs.add(fileDoc(file));
                }
            }
        }
        for (String name : context.getFromUserFiles()) {
            for (AttachmentFile file : userFiles) {
                if (name.equals(file.getName())) docs.add(fileDoc(file));
            }
        }
        return docs;
    }

    private static Doc fileDoc(AttachmentFile file) {
        byte[] data = file.getData() == null ? new byte[0] : file.getData();
        return new Doc(null, file, new String(data, StandardCharsets.UTF_8));
    }

    private static Information matchOrNew(List<Information> facts, String line) {
        for (Information fact : facts) {
            if (line.equals(fact.getBody())) return fact;
        }
        return new Information(line, InformationKind.INFORMATIVE, null);
    }

    private static String envelope(List<Doc> docs) {
        for (Doc doc : docs) {
            if (doc.email == null) continue;
            Email email = doc.email;
            return "from: " + (email.getSender() == null ? "" : email.getSender().getEmail())
                    + "\nto: " + email.getRecipients()
                    + "\nsubject: " + email.getSubject();
        }
        return "";
    }

    static Map<String, List<String>> blocks(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String key = null;
        StringBuilder value = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.matches("[A-Za-z][A-Za-z0-9]*:")) {
                keep(out, key, value);
                key = line.substring(0, line.length() - 1);
                value = new StringBuilder();
            } else if (key != null) {
                if (value.length() > 0) value.append('\n');
                value.append(line);
            }
        }
        keep(out, key, value);
        return out;
    }

    private static void keep(Map<String, List<String>> out, String key, StringBuilder value) {
        if (key == null) return;
        String text = value.toString().trim();
        if (!text.isEmpty()) out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(text);
    }

    private static String first(Map<String, List<String>> parsed, String key) {
        List<String> values = parsed.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
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

    private record Doc(Email email, AttachmentFile file, String text) {}

    public static final class PlannerResult {
        private final List<Question> questions = new ArrayList<>();
        private final List<Instruction> refused = new ArrayList<>();
        private final List<Brief> briefs = new ArrayList<>();
        private final List<Checklist> checklists = new ArrayList<>();
        private final ShortTermMemory shortTerm = new ShortTermMemory();
        private final Trace trace = new Trace();

        PlannerResult(String requestText) {
            trace.setRequestText(requestText);
        }

        public List<Question> getQuestions() { return questions; }
        public List<Instruction> getRefused() { return refused; }
        public List<Brief> getBriefs() { return briefs; }
        public List<Checklist> getChecklists() { return checklists; }
        public ShortTermMemory getShortTerm() { return shortTerm; }
        public Trace getTrace() { return trace; }
    }
}

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
import com.assistant.mail.model.memory.MemoryOrigin;
import com.assistant.mail.model.memory.MemoryRule;
import com.assistant.mail.model.memory.ShortTermMemory;
import com.assistant.mail.model.plan.Context;
import com.assistant.mail.model.plan.Instruction;
import com.assistant.mail.model.plan.InstructionType;
import com.assistant.mail.model.trace.Trace;
import com.assistant.mail.model.trace.TraceActor;
import com.assistant.mail.model.trace.TraceStep;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.service.mcp.MailClientMcp;
import com.assistant.mail.service.agent.PlannerAgentClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Planner {

    private static final int SNIPPET = 80;
    private static final Pattern KEY = Pattern.compile("^([A-Za-z]+):\\s*(.*)$");

    private final MailClientMcp mail;
    private final PlannerAgentClient agent;

    public Planner(MailClientMcp mail, PlannerAgentClient agent) {
        this.mail = mail;
        this.agent = agent;
    }

    public Run run(Request request, LongTermMemory memory) {
        return run(request, memory, (text, brief) -> List.of(), dispute -> false);
    }

    public Run run(Request request, LongTermMemory memory,
                   BiFunction<String, Brief, List<Dispute>> gaps,
                   Predicate<Dispute> criticRight) {
        Run run = new Run();
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            return run;
        }
        LongTermMemory rules = memory == null ? new LongTermMemory() : memory;
        run.trace.setRequestText(request.getText());
        String split = agent.complete(PlannerSystemPrompt.SPLIT, request.getText());
        for (Map<String, String> block : blocks(split)) {
            String body = block.getOrDefault("body", "").strip();
            if (body.isEmpty()) {
                continue;
            }
            one(run, request, body, rules, gaps, criticRight);
        }
        return run;
    }

    private void one(Run run, Request request, String body, LongTermMemory memory,
                     BiFunction<String, Brief, List<Dispute>> gaps,
                     Predicate<Dispute> criticRight) {
        Instruction instruction = new Instruction(body, null, true, false);
        String routed = agent.complete(PlannerSystemPrompt.ROUTE, routePrompt(body, memory));
        Map<String, String> route = first(blocks(routed));
        InstructionType type = enumValue(InstructionType.class, route.get("type"));
        boolean clear = "yes".equalsIgnoreCase(route.getOrDefault("clear", "").strip());
        instruction.setType(type);
        instruction.setIsClear(clear);
        if (type == InstructionType.OUTOFSCOPE) {
            return;
        }
        Language userLanguage = Languages.of(request.getText());
        if (type == null || !clear) {
            run.questions.add(ask(QuestionReason.UNCLEAR, body, userLanguage));
            return;
        }

        List<Email> emails = mail.retrieveRelevantEmails(body);
        List<EmailThread> threads = mail.retrieveRelevantThreads(body);
        List<AttachmentFile> userFiles = request.getFiles() == null ? List.of() : request.getFiles();
        if (emails.isEmpty() && threads.isEmpty() && userFiles.isEmpty()) {
            run.questions.add(ask(QuestionReason.NO_MAIL, body, userLanguage));
            return;
        }
        String located = agent.complete(PlannerSystemPrompt.LOCATE, locatePrompt(body, emails, threads, userFiles));
        if (located == null || located.isBlank() || located.strip().equalsIgnoreCase("none")) {
            run.questions.add(ask(QuestionReason.NO_MAIL, body, userLanguage));
            return;
        }
        Selection selection = select(blocks(located), emails, threads, userFiles);
        if (selection.emails.isEmpty() && selection.files.isEmpty() && selection.userFiles.isEmpty()) {
            run.questions.add(ask(QuestionReason.NO_MAIL, body, userLanguage));
            return;
        }
        run.contexts.add(selection.context);

        List<Information> facts = new ArrayList<>();
        for (Email email : selection.emails) {
            facts.addAll(read(body, "mail:\n" + text(email.getBody()), email.getId(), null));
        }
        for (AttachmentFile file : selection.files) {
            facts.addAll(read(body, "file: " + file.getName() + "\n" + text(file), null, file.getName()));
        }
        for (AttachmentFile file : selection.userFiles) {
            facts.addAll(read(body, "file: " + file.getName() + "\n" + text(file), null, file.getName()));
        }

        String mailBody = selection.emails.isEmpty() ? "" : text(selection.emails.get(0).getBody());
        Language language = Languages.output(instruction.getType(), instruction.getBody(), userLanguage, mailBody);
        Brief brief = brief(instruction, facts, selection, memory, "", language);
        for (Dispute dispute : gaps.apply(request.getText(), brief)) {
            dispute.setAgent(AgentKind.PLANNER);
            dispute.setTask(instruction.getType());
            dispute.setAgentArgument(field(agent.complete(PlannerSystemPrompt.ARGUMENT, argumentPrompt(dispute, brief, facts)), "argument"));
            dispute.setCriticRight(criticRight.test(dispute));
            run.disputes.getDisputes().add(dispute);
            TraceStep step = new TraceStep();
            step.setAt(Instant.now());
            step.setActor(TraceActor.PICKER);
            step.setAction("criticRight");
            step.setDetail(String.valueOf(Boolean.TRUE.equals(dispute.getCriticRight())));
            run.trace.getSteps().add(step);
            if (Boolean.TRUE.equals(dispute.getCriticRight())) {
                String lesson = lesson(agent.complete(PlannerSystemPrompt.LESSON, lessonPrompt(dispute, userLanguage)));
                dispute.setLesson(lesson);
                if (!lesson.isEmpty()) {
                    memory.getRules().add(rule(instruction.getType(), dispute, lesson));
                }
                brief = brief(instruction, facts, selection, memory, dispute.getCriticArgument(), language);
            }
        }
        brief.setEmails(selection.emails);
        brief.setAttachments(selection.files);
        run.briefs.add(brief);

        Checklist checklist = checklist(brief);
        run.checklists.add(checklist);
        for (ChecklistItem item : checklist.getItems()) {
            if (item.getExpected() == null || item.getExpected().isBlank()) {
                run.questions.add(ask(QuestionReason.MISSING_FACT, item.getPart() + " missing from the brief", userLanguage));
            }
        }
    }

    private List<Information> read(String instruction, String document, Long sourceEmailId, String sourceFile) {
        if (document.isBlank()) {
            return List.of();
        }
        String raw = agent.complete(PlannerSystemPrompt.READ, "instruction:\n" + instruction + "\n\n" + document);
        List<Information> facts = new ArrayList<>();
        for (Map<String, String> block : blocks(raw)) {
            String body = block.getOrDefault("body", "").strip();
            if (body.isEmpty()) {
                continue;
            }
            InformationKind kind = enumValue(InformationKind.class, block.get("kind"));
            if (kind == null) {
                kind = InformationKind.INFORMATIVE;
            }
            Information fact = new Information(body, kind, instant(block.get("dueTime")));
            fact.setSourceEmailId(sourceEmailId);
            fact.setSourceFile(sourceFile);
            facts.add(fact);
        }
        return facts;
    }

    private Brief brief(Instruction instruction, List<Information> facts, Selection selection,
                        LongTermMemory memory, String correction, Language language) {
        String raw = agent.complete(PlannerSystemPrompt.BRIEF,
                briefPrompt(instruction, facts, selection, memory, correction, language));
        Brief brief = new Brief();
        brief.setTask(instruction.getType());
        brief.setTo(values(raw, "to"));
        brief.setCc(values(raw, "cc"));
        brief.setLanguage(language);
        brief.setRecipientRole(emptyToNull(field(raw, "recipientRole")));
        brief.setTone(enumValue(DraftTone.class, field(raw, "tone")));
        brief.setStructure(enumValue(DraftStructure.class, field(raw, "structure")));
        brief.setStyleNotes(values(raw, "styleNote"));
        brief.setFacts(matchFacts(values(raw, "fact"), facts));
        if (instruction.getType() == InstructionType.REPLY && !selection.emails.isEmpty()) {
            brief.setReplyToId(selection.emails.get(0).getId());
        }
        brief.setDoNotFollow(values(raw, "doNotFollow"));
        brief.setContextFiles(selection.userFiles);
        brief.setEmails(selection.emails);
        return brief;
    }

    private Checklist checklist(Brief brief) {
        String raw = agent.complete(PlannerSystemPrompt.CHECKLIST, "brief:\n" + briefText(brief));
        Checklist checklist = new Checklist();
        for (Map<String, String> block : blocks(raw)) {
            BriefPart part = enumValue(BriefPart.class, block.get("part"));
            if (part == null) {
                continue;
            }
            ChecklistItem item = new ChecklistItem();
            item.setPart(part);
            item.setExpected(block.getOrDefault("expected", "").strip());
            item.setChecked(!item.getExpected().isBlank());
            checklist.getItems().add(item);
        }
        return checklist;
    }

    private Question ask(QuestionReason reason, String gap, Language language) {
        String user = "language: " + language + "\n" + (reason == QuestionReason.UNCLEAR ? gap : "reason: " + reason + "\n" + gap);
        return new Question(text(agent.complete(PlannerSystemPrompt.ASK_USER, user)), reason, List.of());
    }

    private static String lesson(String raw) {
        String parsed = field(raw, "lesson");
        return parsed == null ? firstLine(raw) : parsed;
    }

    static List<MemoryRule> rulesFor(LongTermMemory memory, InstructionType task) {
        return PlannerMemory.forPlanner(memory, task);
    }

    private static MemoryRule rule(InstructionType task, Dispute dispute, String lesson) {
        MemoryRule rule = new MemoryRule();
        rule.setText(lesson);
        rule.setAgent(AgentKind.PLANNER);
        rule.setTask(task);
        rule.setPart(dispute.getPart());
        rule.setOrigin(MemoryOrigin.CRITIC);
        rule.setReason(dispute.getCriticArgument());
        return rule;
    }

    private static String routePrompt(String body, LongTermMemory memory) {
        StringBuilder prompt = new StringBuilder("instruction:\n").append(body).append("\nrules:\n");
        for (MemoryRule rule : rulesFor(memory, null)) {
            prompt.append("- ").append(rule.getText()).append('\n');
        }
        return prompt.toString();
    }

    private static String locatePrompt(String body, List<Email> emails, List<EmailThread> threads,
                                       List<AttachmentFile> userFiles) {
        StringBuilder prompt = new StringBuilder("instruction:\n").append(body).append("\nhits:\n");
        for (Email email : emails) {
            prompt.append("email: ").append(email.getId())
                    .append("\nsubject: ").append(text(email.getSubject()))
                    .append("\nsnippet: ").append(snippet(email.getBody()))
                    .append("\nfiles: ");
            for (AttachmentFile file : filesOf(email)) {
                prompt.append(file.getName()).append(' ');
            }
            prompt.append("\n\n");
        }
        for (EmailThread thread : threads) {
            prompt.append("thread: ").append(text(thread.getThreadId())).append("\nsubjects:");
            for (Email email : thread.getEmails()) {
                prompt.append(' ').append(text(email.getSubject()));
            }
            prompt.append("\n\n");
        }
        for (AttachmentFile file : userFiles) {
            prompt.append("userFile: ").append(file.getName()).append("\n");
        }
        return prompt.toString();
    }

    private static String briefPrompt(Instruction instruction, List<Information> facts, Selection selection,
                                      LongTermMemory memory, String correction, Language language) {
        StringBuilder prompt = new StringBuilder("language: ").append(language)
                .append("\ninstruction:\n").append(instruction.getBody())
                .append("\ntype: ").append(instruction.getType()).append("\nfacts:\n");
        for (Information fact : facts) {
            prompt.append("- ").append(stamped(fact)).append('\n');
        }
        prompt.append("envelope:\n");
        for (Email email : selection.emails) {
            prompt.append("id: ").append(email.getId())
                    .append("\nsubject: ").append(text(email.getSubject()))
                    .append("\nto: ").append(email.getRecipients()).append("\n");
        }
        prompt.append("rules:\n");
        for (MemoryRule rule : rulesFor(memory, instruction.getType())) {
            prompt.append("- ").append(rule.getText()).append('\n');
        }
        if (correction != null && !correction.isBlank()) {
            prompt.append("correction:\n").append(correction).append('\n');
        }
        return prompt.toString();
    }

    private static String argumentPrompt(Dispute dispute, Brief brief, List<Information> facts) {
        StringBuilder prompt = new StringBuilder("gap:\n").append(text(dispute.getCriticArgument())).append("\nfacts:\n");
        for (Information fact : facts) {
            prompt.append("- ").append(stamped(fact)).append('\n');
        }
        prompt.append("brief:\n").append(briefText(brief));
        return prompt.toString();
    }

    private static String lessonPrompt(Dispute dispute, Language language) {
        return "language: " + language + "\ngap:\n" + text(dispute.getCriticArgument()) + "\nanswer:\n" + text(dispute.getAgentArgument());
    }

    private static String briefText(Brief brief) {
        StringBuilder text = new StringBuilder("task: ").append(brief.getTask())
                .append("\nto: ").append(brief.getTo())
                .append("\ncc: ").append(brief.getCc())
                .append("\nfacts:\n");
        for (Information fact : brief.getFacts()) {
            text.append("- ").append(stamped(fact)).append('\n');
        }
        return text.append("replyTo: ").append(brief.getReplyToId())
                .append("\ndoNotFollow: ").append(brief.getDoNotFollow())
                .toString();
    }

    private static Selection select(List<Map<String, String>> chosen, List<Email> emails,
                                    List<EmailThread> threads, List<AttachmentFile> userFiles) {
        Selection selection = new Selection();
        for (Map<String, String> block : chosen) {
            Long id = longValue(block.get("email"));
            if (id != null) {
                Email email = findEmail(emails, threads, id);
                if (email != null && !selection.emails.contains(email)) {
                    selection.emails.add(email);
                    selection.context.getEmailIds().add(id);
                }
            }
            String threadId = emptyToNull(block.get("thread"));
            if (threadId != null) {
                selection.context.getThreadIds().add(threadId);
                for (EmailThread thread : threads) {
                    if (threadId.equals(thread.getThreadId())) {
                        for (Email email : thread.getEmails()) {
                            if (!selection.emails.contains(email)) {
                                selection.emails.add(email);
                            }
                        }
                    }
                }
            }
            String name = emptyToNull(block.get("attachment"));
            if (name != null) {
                AttachmentFile file = findFile(emails, threads, name);
                if (file != null) {
                    selection.files.add(file);
                    selection.context.getAttachedFiles().add(name);
                }
            }
            String userFile = emptyToNull(block.get("userFile"));
            if (userFile != null) {
                for (AttachmentFile file : userFiles) {
                    if (userFile.equals(file.getName())) {
                        selection.userFiles.add(file);
                        selection.context.getFromUserFiles().add(userFile);
                    }
                }
            }
        }
        return selection;
    }

    private static Email findEmail(List<Email> emails, List<EmailThread> threads, Long id) {
        for (Email email : emails) {
            if (id.equals(email.getId())) {
                return email;
            }
        }
        for (EmailThread thread : threads) {
            for (Email email : thread.getEmails()) {
                if (id.equals(email.getId())) {
                    return email;
                }
            }
        }
        return null;
    }

    private static AttachmentFile findFile(List<Email> emails, List<EmailThread> threads, String name) {
        for (Email email : emails) {
            for (AttachmentFile file : filesOf(email)) {
                if (name.equals(file.getName())) {
                    return file;
                }
            }
        }
        for (EmailThread thread : threads) {
            for (Email email : thread.getEmails()) {
                for (AttachmentFile file : filesOf(email)) {
                    if (name.equals(file.getName())) {
                        return file;
                    }
                }
            }
        }
        return null;
    }

    private static List<AttachmentFile> filesOf(Email email) {
        return email.getAttachments() == null ? List.of() : email.getAttachments();
    }

    private static String stamped(Information fact) {
        if (fact.getSourceEmailId() != null) {
            return "mail " + fact.getSourceEmailId() + ": " + fact.getBody();
        }
        if (fact.getSourceFile() != null && !fact.getSourceFile().isBlank()) {
            return "file " + fact.getSourceFile() + ": " + fact.getBody();
        }
        return fact.getBody();
    }

    private static List<Information> matchFacts(List<String> bodies, List<Information> facts) {
        List<Information> matched = new ArrayList<>();
        for (String body : bodies) {
            Information found = null;
            for (Information fact : facts) {
                if (fact.getBody().equals(body)) {
                    found = fact;
                    break;
                }
            }
            matched.add(found == null ? new Information(body, InformationKind.INFORMATIVE, null) : found);
        }
        return matched;
    }

    static List<Map<String, String>> blocks(String raw) {
        List<Map<String, String>> out = new ArrayList<>();
        Map<String, String> current = null;
        String key = null;
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\n", -1)) {
            Matcher matcher = KEY.matcher(line);
            if (!matcher.matches()) {
                if (key != null) {
                    current.put(key, current.get(key) + "\n" + line);
                }
                continue;
            }
            String next = matcher.group(1);
            if (current != null && current.containsKey(next)) {
                out.add(current);
                current = null;
            }
            if (current == null) {
                current = new LinkedHashMap<>();
            }
            current.put(next, matcher.group(2));
            key = next;
        }
        if (current != null && !current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    private static List<String> values(String raw, String key) {
        List<String> values = new ArrayList<>();
        for (Map<String, String> block : blocks(raw)) {
            String value = block.get(key);
            if (value != null && !value.isBlank()) {
                values.add(value.strip());
            }
        }
        return values;
    }

    private static String field(String raw, String key) {
        String found = null;
        for (Map<String, String> block : blocks(raw)) {
            String value = block.get(key);
            if (value != null && !value.isBlank()) {
                found = value.strip();
            }
        }
        return found;
    }

    private static Map<String, String> first(List<Map<String, String>> blocks) {
        return blocks.isEmpty() ? Map.of() : blocks.get(0);
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.strip());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static Long longValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.strip());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Instant instant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String snippet(String body) {
        String value = text(body);
        int line = value.indexOf('\n');
        if (line >= 0) {
            value = value.substring(0, line);
        }
        return value.length() <= SNIPPET ? value : value.substring(0, SNIPPET);
    }

    private static String text(AttachmentFile file) {
        if (file.getData() == null) {
            return "";
        }
        return new String(file.getData(), StandardCharsets.UTF_8);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String firstLine(String value) {
        String text = text(value);
        int line = text.indexOf('\n');
        return line < 0 ? text : text.substring(0, line).strip();
    }

    private static String emptyToNull(String value) {
        String text = text(value);
        return text.isEmpty() ? null : text;
    }

    private static final class Selection {
        private final Context context = new Context();
        private final List<Email> emails = new ArrayList<>();
        private final List<AttachmentFile> files = new ArrayList<>();
        private final List<AttachmentFile> userFiles = new ArrayList<>();
    }

    public static final class Run {
        public final List<Question> questions = new ArrayList<>();
        public final List<Brief> briefs = new ArrayList<>();
        public final List<Checklist> checklists = new ArrayList<>();
        public final List<Context> contexts = new ArrayList<>();
        public final ShortTermMemory disputes = new ShortTermMemory();
        public final Trace trace = new Trace();
    }
}

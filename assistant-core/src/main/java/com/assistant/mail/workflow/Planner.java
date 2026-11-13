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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Planner {

    private static final int SNIPPET = 80;
    private static final Pattern KEY = Pattern.compile("^([A-Za-z]+):\\s*(.*)$");

    private final MailClientMcp mail;
    private final PlannerAgentClient agent;
    private com.assistant.mail.context.KnowledgeSource knowledge;

    public Planner(MailClientMcp mail, PlannerAgentClient agent) {
        this.mail = mail;
        this.agent = agent;
    }

    /** Optional: contact cards and profile passages added to each brief. */
    public Planner withKnowledge(com.assistant.mail.context.KnowledgeSource source) {
        this.knowledge = source;
        return this;
    }

    private void addKnowledge(Brief brief, Instruction instruction, List<Information> facts, Selection selection) {
        if (knowledge == null) return;
        String recipient = brief.getTo().isEmpty() ? "" : brief.getTo().get(0);
        boolean aboutTheSender = brief.getTask() == InstructionType.REPLY || brief.getTask() == InstructionType.SUMMARIZE;
        if ((recipient.isBlank() || aboutTheSender) && !selection.emails.isEmpty()
                && selection.emails.get(0).getSender() != null && selection.emails.get(0).getSender().getEmail() != null) {
            recipient = selection.emails.get(0).getSender().getEmail();
        }
        StringBuilder query = new StringBuilder(instruction.getBody() == null ? "" : instruction.getBody());
        for (Information fact : facts) query.append(' ').append(fact.getBody());
        try {
            List<String> background = new ArrayList<>(knowledge.knowledgeFor(recipient, brief.getRecipientRole(), query.toString()));
            // a summary is about the mails only: profile passages would be summarised instead of them
            if (brief.getTask() == InstructionType.SUMMARIZE) background.removeIf(line -> !line.startsWith("Contact "));
            brief.setKnowledge(background);
        } catch (RuntimeException e) {
            brief.setKnowledge(new ArrayList<>()); // background is optional: never block the task on it
        }
    }

    public Run run(Request request, LongTermMemory memory) {
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
            one(run, request, body, rules);
        }
        return run;
    }

    private void one(Run run, Request request, String body, LongTermMemory memory) {
        Instruction instruction = new Instruction(body, null, true, false);
        String routed = agent.complete(PlannerSystemPrompt.ROUTE, routePrompt(body, memory));
        Map<String, String> route = first(blocks(routed));
        InstructionType type = enumValue(InstructionType.class, route.get("type"));
        boolean clear = "yes".equalsIgnoreCase(route.getOrDefault("clear", "").strip());
        instruction.setType(type);
        instruction.setIsClear(clear);
        Language userLanguage = Languages.of(request.getText());
        if (type == InstructionType.OUTOFSCOPE) {
            run.refused.add(Languages.refusal(userLanguage));
            return;
        }
        if (type == InstructionType.CHAT) {
            run.chats.add(text(agent.complete(PlannerSystemPrompt.ANSWER, "language: " + userLanguage + "\n" + body)));
            return;
        }
        if (type == null || !clear) {
            run.questions.add(ask(QuestionReason.UNCLEAR, body, userLanguage));
            return;
        }

        Gather gather = open(body);
        if (gather.blocked != null) {
            run.questions.add(ask(QuestionReason.MISSING_FACT, gather.blocked, userLanguage));
            return;
        }
        List<Email> emails = gather.emails;
        List<EmailThread> threads = gather.threads;
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
        if (asksForThread(body)) expandThreads(selection);
        run.contexts.add(selection.context);

        readInFull(selection);
        List<Information> facts = new ArrayList<>();
        Map<Long, String> safeBodies = new LinkedHashMap<>();
        for (Email email : selection.emails) {
            String safe = guard(run, userLanguage, email.getId(), null, text(email.getBody()));
            if (email.getId() != null) safeBodies.put(email.getId(), safe);
            facts.addAll(read(body, "mail:\n" + safe, email.getId(), null));
        }
        for (AttachmentFile file : selection.files) {
            String safe = guard(run, userLanguage, null, file.getName(), text(file));
            facts.addAll(read(body, "file: " + file.getName() + "\n" + safe, null, file.getName()));
        }
        for (AttachmentFile file : selection.userFiles) {
            String safe = guard(run, userLanguage, null, file.getName(), text(file));
            facts.addAll(read(body, "file: " + file.getName() + "\n" + safe, null, file.getName()));
        }

        String mailBody = selection.emails.isEmpty() ? "" : text(selection.emails.get(0).getBody());
        Language language = Languages.output(instruction.getType(), instruction.getBody(), userLanguage, mailBody);
        Brief brief = brief(instruction, facts, selection, memory, language);
        brief.setAttachments(selection.files);
        if (brief.getFacts().isEmpty()) brief.setFacts(new ArrayList<>(facts)); // the model kept none: keep all that were read
        addSourceFacts(brief, selection, safeBodies);
        addKnowledge(brief, instruction, facts, selection);
        for (Map<String, String> gap : blocks(agent.complete(PlannerSystemPrompt.CRITIC, criticPrompt(instruction, brief, facts)))) {
            BriefPart part = enumValue(BriefPart.class, gap.get("part"));
            String argument = text(gap.get("argument"));
            if (part == null || argument.isEmpty()) {
                continue;
            }
            Dispute dispute = new Dispute();
            dispute.setAgent(AgentKind.PLANNER);
            dispute.setTask(instruction.getType());
            dispute.setPart(part);
            dispute.setCriticArgument(argument);
            dispute.setCorrection(text(gap.get("instead")));
            String value = text(gap.get("value"));
            dispute.setAgentArgument(field(agent.complete(PlannerSystemPrompt.ARGUMENT, argumentPrompt(dispute, value, brief, facts)), "argument"));
            boolean criticWins = "critic".equalsIgnoreCase(field(agent.complete(PlannerSystemPrompt.JUDGE, judgePrompt(instruction, dispute, value, brief, facts)), "winner"));
            dispute.setCriticRight(criticWins);
            if (criticWins) {
                apply(brief, part, value, dispute.getCorrection(), facts);
            }
            dispute.setLesson(lesson(agent.complete(PlannerSystemPrompt.LESSON, lessonPrompt(dispute, userLanguage))));
            run.disputes.getDisputes().add(dispute);
            TraceStep step = new TraceStep();
            step.setAt(Instant.now());
            step.setActor(TraceActor.PICKER);
            step.setAction("criticRight");
            step.setDetail(String.valueOf(criticWins));
            run.trace.getSteps().add(step);
        }
        run.briefs.add(brief);

        Checklist checklist = checklist(brief);
        run.checklists.add(checklist);
        for (ChecklistItem item : checklist.getItems()) {
            if (item.getExpected() == null || item.getExpected().isBlank()) {
                run.questions.add(ask(QuestionReason.MISSING_FACT, item.getPart() + " missing from the brief", userLanguage));
            }
        }
    }

    /** ponytail: four fetches, then stop. Raise the cap if search plus read needs more hops. */
    private Gather open(String body) {
        Set<String> mails = new LinkedHashSet<>();
        Set<String> threads = new LinkedHashSet<>();
        List<Email> emails = new ArrayList<>();
        List<EmailThread> found = new ArrayList<>();
        String rejection = "";
        int rejects = 0;
        int premature = 0;
        String blocked = null;
        boolean forbidden = false; // a tool outside the allowed three: never worked around
        for (int step = 0; step < 4; step++) {
            String raw = agent.complete(PlannerSystemPrompt.TOOLS, toolPrompt(body, emails, rejection));
            List<ToolGate.Call> calls = toolCalls(raw);
            if (calls.isEmpty()) {
                // done before any search: nothing was fetched, so ask once more instead of giving up
                if (emails.isEmpty() && found.isEmpty() && ++premature <= 1) {
                    rejection = "rejected done: no mail was fetched yet. Call search_mail first with a short query "
                            + "(a sender name or one keyword).";
                    continue;
                }
                break;
            }
            int emailCount = emails.size();
            int threadCount = found.size();
            StringBuilder bad = new StringBuilder();
            for (ToolGate.Call call : calls) {
                String why = ToolGate.deny(call, mails, threads, null, false);
                if (why != null) {
                    bad.append(why).append('\n');
                    if (!java.util.Set.of("search_mail", "read_mail", "read_thread").contains(call.name())) forbidden = true;
                    continue;
                }
                String failed = runCall(call, mails, threads, emails, found);
                if (failed != null) bad.append(failed).append('\n');
            }
            if (!bad.isEmpty()) {
                if (++rejects > 1) {
                    blocked = bad.toString().strip();
                    break;
                }
                rejection = bad.toString().strip();
            } else if (emails.size() == emailCount && found.size() == threadCount) {
                break;
            } else {
                rejection = "";
            }
        }
        if (emails.isEmpty() && found.isEmpty() && !forbidden) {
            // the model never produced a usable search: try the distinctive words of the instruction
            for (String word : searchWords(body)) {
                List<Email> hits;
                try {
                    hits = mail.searchMail(word);
                } catch (RuntimeException e) {
                    continue;
                }
                if (hits == null || hits.isEmpty()) continue;
                for (Email email : hits) note(email, mails, threads, emails);
                blocked = null;
                break;
            }
        }
        return new Gather(emails, found, emails.isEmpty() && found.isEmpty() ? blocked : null);
    }

    /** Capitalised words first (names), longest first, then other long words. At most four. */
    public static List<String> searchWords(String body) {
        List<String> names = new ArrayList<>();
        List<String> others = new ArrayList<>();
        String[] tokens = body.split("[^\\p{L}\\p{N}@._-]+");
        for (int i = 0; i < tokens.length; i++) {
            String w = tokens[i].replaceAll("^[._-]+|[._-]+$", "");
            if (w.length() < 3) continue;
            boolean capital = Character.isUpperCase(w.codePointAt(0));
            if (capital && i > 0 && !names.contains(w)) names.add(w);
            else if (!capital && w.length() >= 6 && !others.contains(w)) others.add(w);
        }
        names.sort((a, b) -> b.length() - a.length());
        others.sort((a, b) -> b.length() - a.length());
        List<String> words = new ArrayList<>(names);
        words.addAll(others);
        return words.subList(0, Math.min(4, words.size()));
    }

    private String runCall(ToolGate.Call call, Set<String> mails, Set<String> threads, List<Email> emails, List<EmailThread> found) {
        try {
            switch (call.name()) {
                case "search_mail" -> {
                    List<Email> hits = mail.searchMail(call.query());
                    if (hits != null) for (Email email : hits) note(email, mails, threads, emails);
                    return null;
                }
                case "read_mail" -> {
                    Email email = mail.readMail(call.id());
                    if (email == null) return "rejected read_mail: empty result";
                    note(email, mails, threads, emails);
                    return null;
                }
                case "read_thread" -> {
                    EmailThread thread = mail.readThread(call.threadId());
                    if (thread == null || thread.getEmails() == null || thread.getEmails().isEmpty()) {
                        return "rejected read_thread: empty result";
                    }
                    if (thread.getThreadId() != null) threads.add(thread.getThreadId());
                    if (found.stream().noneMatch(item -> thread.getThreadId() != null && thread.getThreadId().equals(item.getThreadId()))) {
                        found.add(thread);
                    }
                    for (Email email : thread.getEmails()) note(email, mails, threads, emails);
                    return null;
                }
                default -> {
                    return "rejected " + call.name() + ": unknown tool";
                }
            }
        } catch (RuntimeException e) {
            return "rejected " + call.name() + ": " + e.getMessage();
        }
    }

    private static void note(Email email, Set<String> mails, Set<String> threads, List<Email> emails) {
        if (email == null) return;
        if (email.getId() != null) mails.add(String.valueOf(email.getId()));
        if (email.getThreadId() != null && !email.getThreadId().isBlank()) threads.add(email.getThreadId());
        for (int i = 0; i < emails.size(); i++) {
            if (email.getId() != null && email.getId().equals(emails.get(i).getId())) {
                emails.set(i, email);
                return;
            }
        }
        emails.add(email);
    }

    private String toolPrompt(String body, List<Email> emails, String rejection) {
        StringBuilder prompt = new StringBuilder("instruction:\n").append(body).append("\nhits:\n");
        for (Email email : emails) {
            prompt.append("email: ").append(email.getId())
                    .append("\nsubject: ").append(text(email.getSubject()))
                    .append("\nsnippet: ").append(snippet(email.getBody()));
            if (email.getThreadId() != null) prompt.append("\nthread: ").append(email.getThreadId());
            if (mail.unread(email.getId())) prompt.append("\nunread: yes");
            prompt.append("\n\n");
        }
        if (!rejection.isEmpty()) prompt.append("rejected:\n").append(rejection).append('\n');
        return prompt.toString();
    }

    private static List<ToolGate.Call> toolCalls(String raw) {
        List<ToolGate.Call> calls = new ArrayList<>();
        for (Map<String, String> block : blocks(raw)) {
            String name = text(block.get("name"));
            if (name.isEmpty()) continue;
            calls.add(ToolGate.read(name, text(block.get("query")), text(block.get("id")), text(block.get("threadId"))));
        }
        return calls;
    }

    private static final Pattern THREAD_WORDS = Pattern.compile(
            "(?i)\\b(fil|thread|conversation|discussion|[eé]changes?|cha[iî]ne)\\b");

    public static boolean asksForThread(String instruction) {
        return instruction != null && THREAD_WORDS.matcher(instruction).find();
    }

    /** "Summarize the thread": every mail of the thread of a selected mail is read, oldest first. */
    private void expandThreads(Selection selection) {
        for (Email chosen : new ArrayList<>(selection.emails)) {
            String threadId = chosen.getThreadId();
            if (threadId == null || threadId.isBlank()) continue;
            try {
                EmailThread thread = mail.readThread(threadId);
                if (thread == null) continue;
                selection.context.getThreadIds().add(threadId);
                for (Email email : thread.getEmails()) {
                    int at = -1;
                    for (int i = 0; i < selection.emails.size(); i++) {
                        Long known = selection.emails.get(i).getId();
                        if (known != null && known.equals(email.getId())) at = i;
                    }
                    if (at < 0) selection.emails.add(email);
                    else selection.emails.set(at, email); // the thread read is the full mail, not the search snippet
                }
            } catch (RuntimeException e) {
                // keep what was selected
            }
        }
    }

    /**
     * A small model keeps the gist of a mail and loses the date and the room. A short mail is therefore also
     * given to the writer whole, as one fact, so concrete details are never lost between reading and writing.
     */
    private static void addSourceFacts(Brief brief, Selection selection, Map<Long, String> safeBodies) {
        if (brief.getTask() == InstructionType.SUMMARIZE) return; // a summary built from whole mails just copies them
        for (Email email : selection.emails) {
            String safe = email.getId() == null ? null : safeBodies.get(email.getId());
            if (safe == null || safe.isBlank() || safe.length() > 1200) continue;
            boolean present = brief.getFacts().stream().anyMatch(f -> safe.equals(f.getBody()));
            if (present) continue;
            Information whole = new Information(safe, InformationKind.INFORMATIVE, null);
            whole.setSourceEmailId(email.getId());
            brief.getFacts().add(whole);
        }
    }

    /** A search hit is only a snippet: open it with read_mail so the facts come from the whole mail. */
    private void readInFull(Selection selection) {
        for (int i = 0; i < selection.emails.size(); i++) {
            Email hit = selection.emails.get(i);
            if (hit.getId() == null) continue;
            try {
                if (!mail.unread(hit.getId())) continue;
                Email full = mail.readMail(String.valueOf(hit.getId()));
                if (full != null && full.getBody() != null) {
                    if (full.getId() == null) full.setId(hit.getId());
                    if (full.getSender() == null) full.setSender(hit.getSender());
                    selection.emails.set(i, full);
                }
            } catch (RuntimeException e) {
                // keep the snippet: a failed read must not stop the task
            }
        }
    }

    /** Removes orders hidden in the text, records them, and returns the text the models may read. */
    private static String guard(Run run, Language language, Long emailId, String file, String text) {
        InjectionGuard.Result scanned = InjectionGuard.scan(text);
        for (String segment : scanned.suspicious()) {
            run.flagged.add(new Flag(emailId, file, segment));
            String where = emailId != null ? (language == Language.FR ? "le mail " : "mail ") + emailId
                    : (language == Language.FR ? "le fichier " : "file ") + file;
            run.warnings.add(language == Language.FR
                    ? "Attention : " + where + " contient une instruction qui ne vient pas de vous, ignorée : « "
                            + InjectionGuard.quote(segment) + " »"
                    : "Warning: " + where + " contains an instruction that did not come from you, ignored: \""
                            + InjectionGuard.quote(segment) + "\"");
        }
        return scanned.clean();
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
                        LongTermMemory memory, Language language) {
        String raw = agent.complete(PlannerSystemPrompt.BRIEF,
                briefPrompt(instruction, facts, selection, memory, language));
        Brief brief = new Brief();
        brief.setTask(instruction.getType());
        brief.setTo(addresses(values(raw, "to")));
        brief.setCc(addresses(values(raw, "cc")));
        if (instruction.getType() == InstructionType.REPLY && !selection.emails.isEmpty()) {
            fixReplyRecipient(brief, selection.emails.get(0), instruction.getBody());
        }
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

    /** {@code [a@b.c]}, {@code <a@b.c>}, {@code "a@b.c"} and placeholders like {@code -} or {@code []} become clean addresses. */
    public static List<String> addresses(List<String> raw) {
        List<String> out = new ArrayList<>();
        for (String value : raw) {
            for (String part : value.split(",")) {
                String a = part.strip().replaceAll("^[\\[<\"'`(\\s]+|[\\]>\"'`)\\s]+$", "");
                if (a.isEmpty() || a.equals("-") || a.equalsIgnoreCase("none") || a.equalsIgnoreCase("empty")) continue;
                if (!out.contains(a)) out.add(a);
            }
        }
        return out;
    }

    /**
     * A reply goes to the sender. An address the model wrote that appears neither in the instruction nor in
     * the mail (the user's own address, or an invented one) is replaced by the sender.
     */
    public static void fixReplyRecipient(Brief brief, Email original, String instruction) {
        String sender = original.getSender() == null ? null : original.getSender().getEmail();
        if (sender == null || sender.isBlank()) return;
        String known = ((instruction == null ? "" : instruction) + " " + sender + " " + String.join(" ", original.getRecipients())
                + " " + (original.getBody() == null ? "" : original.getBody())).toLowerCase();
        boolean hasSender = brief.getTo().stream().anyMatch(sender::equalsIgnoreCase);
        boolean ownAddress = brief.getTo().stream().anyMatch(
                a -> original.getRecipients().stream().anyMatch(a::equalsIgnoreCase));
        boolean invented = brief.getTo().stream().anyMatch(a -> !known.contains(a.toLowerCase()));
        if (!hasSender && (brief.getTo().isEmpty() || ownAddress || invented)) {
            brief.setTo(new ArrayList<>(List.of(sender)));
        }
    }

    public static void fixReplyRecipient(Brief brief, Email original) {
        fixReplyRecipient(brief, original, "");
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
                                      LongTermMemory memory, Language language) {
        StringBuilder prompt = new StringBuilder("language: ").append(language)
                .append("\ninstruction:\n").append(instruction.getBody())
                .append("\ntype: ").append(instruction.getType()).append("\nfacts:\n");
        prompt.append(factsText(facts));
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
        return prompt.toString();
    }

    private static String criticPrompt(Instruction instruction, Brief brief, List<Information> facts) {
        return "instruction:\n" + instruction.getBody() + "\n\nfacts:\n" + factsText(facts) + "brief:\n" + briefText(brief);
    }

    private static String argumentPrompt(Dispute dispute, String value, Brief brief, List<Information> facts) {
        return "part:\n" + dispute.getPart()
                + "\nvalue:\n" + value
                + "\nargument:\n" + text(dispute.getCriticArgument())
                + "\nfacts:\n" + factsText(facts)
                + "brief:\n" + briefText(brief);
    }

    private static String judgePrompt(Instruction instruction, Dispute dispute, String value, Brief brief, List<Information> facts) {
        return "instruction:\n" + instruction.getBody()
                + "\n\npart:\n" + dispute.getPart()
                + "\n\nvalue:\n" + value
                + "\n\ninstead:\n" + text(dispute.getCorrection())
                + "\n\ncritic:\n" + text(dispute.getCriticArgument())
                + "\n\nplanner:\n" + text(dispute.getAgentArgument())
                + "\n\nfacts:\n" + factsText(facts)
                + "brief:\n" + briefText(brief);
    }

    private static String lessonPrompt(Dispute dispute, Language language) {
        String winner = Boolean.TRUE.equals(dispute.getCriticRight()) ? "critic" : "planner";
        return "language: " + language
                + "\nwinner:\n" + winner
                + "\npart:\n" + dispute.getPart()
                + "\nargument:\n" + text(dispute.getCriticArgument())
                + "\nanswer:\n" + text(dispute.getAgentArgument());
    }

    private static String factsText(List<Information> facts) {
        StringBuilder text = new StringBuilder();
        for (Information fact : facts) {
            text.append("- ").append(stamped(fact)).append('\n');
        }
        return text.toString();
    }

    /** Drop or replace one brief value. Attachment, file, and mail picks can only be dropped: a name is not a file. */
    private static void apply(Brief brief, BriefPart part, String value, String instead, List<Information> facts) {
        String next = text(instead);
        switch (part) {
            case FACT -> {
                int index = indexOf(brief.getFacts(), value);
                if (index < 0) {
                    return;
                }
                if (next.isEmpty()) {
                    brief.getFacts().remove(index);
                } else {
                    brief.getFacts().set(index, matchFacts(List.of(next), facts).get(0));
                }
            }
            case TO -> swap(brief.getTo(), value, next);
            case CC -> swap(brief.getCc(), value, next);
            case BCC -> swap(brief.getBcc(), value, next);
            case STYLE_NOTE -> swap(brief.getStyleNotes(), value, next);
            case DO_NOT_FOLLOW -> swap(brief.getDoNotFollow(), value, next);
            case LANGUAGE -> brief.setLanguage(next.isEmpty() ? null : enumValue(Language.class, next));
            case TONE -> brief.setTone(next.isEmpty() ? null : enumValue(DraftTone.class, next));
            case STRUCTURE -> brief.setStructure(next.isEmpty() ? null : enumValue(DraftStructure.class, next));
            case TASK -> brief.setTask(next.isEmpty() ? null : enumValue(InstructionType.class, next));
            case RECIPIENT_ROLE -> brief.setRecipientRole(next.isEmpty() ? null : next);
            case REPLY_TO -> brief.setReplyToId(next.isEmpty() ? null : longValue(next));
            case ATTACHMENT -> {
                // ponytail: a replacement name has no bytes; drop only. Add the file when the critic can point at one.
                if (next.isEmpty()) brief.getAttachments().removeIf(file -> value.equals(file.getName()));
            }
            case CONTEXT_FILE -> {
                if (next.isEmpty()) brief.getContextFiles().removeIf(file -> value.equals(file.getName()));
            }
            case EMAIL -> {
                if (next.isEmpty()) brief.getEmails().removeIf(email -> value.equals(String.valueOf(email.getId())));
            }
        }
    }

    private static int indexOf(List<Information> facts, String value) {
        for (int i = 0; i < facts.size(); i++) {
            if (value.equals(facts.get(i).getBody())) {
                return i;
            }
        }
        return -1;
    }

    private static void swap(List<String> values, String value, String instead) {
        int index = values.indexOf(value);
        if (index < 0) {
            return;
        }
        if (instead.isEmpty()) {
            values.remove(index);
        } else {
            values.set(index, instead);
        }
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

    private static final class Gather {
        private final List<Email> emails;
        private final List<EmailThread> threads;
        private final String blocked;

        private Gather(List<Email> emails, List<EmailThread> threads, String blocked) {
            this.emails = emails;
            this.threads = threads;
            this.blocked = blocked;
        }
    }

    private static final class Selection {
        private final Context context = new Context();
        private final List<Email> emails = new ArrayList<>();
        private final List<AttachmentFile> files = new ArrayList<>();
        private final List<AttachmentFile> userFiles = new ArrayList<>();
    }

    public static final class Run {
        public final List<Question> questions = new ArrayList<>();
        public final List<String> refused = new ArrayList<>();
        public final List<String> chats = new ArrayList<>();
        public final List<Brief> briefs = new ArrayList<>();
        public final List<Checklist> checklists = new ArrayList<>();
        public final List<Context> contexts = new ArrayList<>();
        public final ShortTermMemory disputes = new ShortTermMemory();
        public final Trace trace = new Trace();
        /** Orders found inside mails or files and left out of every prompt. */
        public final List<Flag> flagged = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    public record Flag(Long emailId, String file, String text) {}
}

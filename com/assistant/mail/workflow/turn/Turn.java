package com.assistant.mail.workflow.turn;

import com.assistant.mail.workflow.planner.Planner;
import com.assistant.mail.workflow.planner.PlannerMemory;
import com.assistant.mail.workflow.shared.ChecklistProgress;
import com.assistant.mail.workflow.summary.SummaryWorkflow;
import com.assistant.mail.workflow.writer.WriterWorkflow;

import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.ask.QuestionReason;
import com.assistant.mail.model.ask.Request;
import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.brief.Checklist;
import com.assistant.mail.model.memory.Dispute;
import com.assistant.mail.model.memory.LongTermMemory;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.plan.Instruction;
import com.assistant.mail.model.plan.InstructionType;

import java.util.ArrayList;
import java.util.List;

/** One user message: planner, then a writer or a summarizer for each brief. */
public final class Turn {
    private final Planner planner;
    private final WriterWorkflow writer;
    private final SummaryWorkflow summary;

    public Turn(Planner planner, WriterWorkflow writer, SummaryWorkflow summary) {
        this.planner = planner;
        this.writer = writer;
        this.summary = summary;
    }

    public Outcome run(Request request, LongTermMemory memory) {
        Planner.Run planned = planner.run(request, memory);
        Outcome outcome = new Outcome();
        outcome.instructions.addAll(planned.instructions);
        outcome.briefs.addAll(planned.briefs);
        outcome.questions.addAll(planned.questions);
        outcome.refused.addAll(planned.refused);
        outcome.chats.addAll(planned.chats);
        for (int i = 0; i < planned.briefs.size(); i++) {
            Brief brief = planned.briefs.get(i);
            Checklist checklist = i < planned.checklists.size() ? planned.checklists.get(i) : new Checklist();
            if (brief.getTask() == InstructionType.SUMMARIZE) {
                SummaryWorkflow.Result result = summary.run(brief, checklist);
                outcome.summaries.add(result.getSummary());
                outcome.progress.add(ChecklistProgress.apply(checklist, result.getSummary().getBody()));
            } else {
                WriterWorkflow.Result result = writer.run(brief, checklist);
                if (result.getBlocked() != null) {
                    String why = result.getBlocked();
                    if (why.startsWith("rejected create_draft")) why = "I need a recipient before I can save a draft.";
                    outcome.questions.add(new Question(why, QuestionReason.MISSING_FACT, List.of()));
                } else {
                    outcome.drafts.add(result.getDraft());
                    outcome.replyTo.add(brief.getReplyToId());
                    outcome.progress.add(ChecklistProgress.apply(checklist, scored(result.getDraft(), brief)));
                }
            }
        }
        for (Dispute dispute : planned.disputes.getDisputes()) PlannerMemory.remember(memory, dispute);
        return outcome;
    }

    private static String scored(Draft draft, Brief brief) {
        StringBuilder text = new StringBuilder();
        for (String address : draft.getRecipients()) text.append(address).append('\n');
        for (String address : draft.getCc()) text.append(address).append('\n');
        for (String address : draft.getBcc()) text.append(address).append('\n');
        text.append(draft.getSubject()).append('\n').append(draft.getBody()).append('\n');
        if (brief.getReplyToId() != null) text.append(brief.getReplyToId()).append('\n');
        return text.toString();
    }

    public static final class Outcome {
        public final List<Instruction> instructions = new ArrayList<>();
        public final List<Brief> briefs = new ArrayList<>();
        public final List<Question> questions = new ArrayList<>();
        public final List<String> refused = new ArrayList<>();
        public final List<String> chats = new ArrayList<>();
        public final List<Draft> drafts = new ArrayList<>();
        public final List<Long> replyTo = new ArrayList<>();
        public final List<Summary> summaries = new ArrayList<>();
        public final List<String> progress = new ArrayList<>();
    }
}

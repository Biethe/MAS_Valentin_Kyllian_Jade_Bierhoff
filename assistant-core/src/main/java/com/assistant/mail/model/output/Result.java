package com.assistant.mail.model.output;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.plan.InstructionType;

import java.util.ArrayList;
import java.util.List;

public class Result {
    private InstructionType task;
    private String message = "";
    private Draft draft;
    private Summary summary;
    private List<Email> mails = new ArrayList<>();
    private List<AttachmentFile> documents = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
    private List<String> suggestions = new ArrayList<>();
    private UserDecision decision;
    private String feedback = "";

    public Result() {}

    public InstructionType getTask() { return task; }
    public void setTask(InstructionType task) { this.task = task; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message == null ? "" : message; }

    public Draft getDraft() { return draft; }
    public void setDraft(Draft draft) { this.draft = draft; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<Email> getMails() { return mails; }
    public void setMails(List<Email> mails) { this.mails = emptyIfNull(mails); }

    public List<AttachmentFile> getDocuments() { return documents; }
    public void setDocuments(List<AttachmentFile> documents) { this.documents = emptyIfNull(documents); }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = emptyIfNull(warnings); }

    public List<String> getSuggestions() { return suggestions; }
    public void setSuggestions(List<String> suggestions) { this.suggestions = emptyIfNull(suggestions); }

    public UserDecision getDecision() { return decision; }
    public void setDecision(UserDecision decision) { this.decision = decision; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback == null ? "" : feedback; }

    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}

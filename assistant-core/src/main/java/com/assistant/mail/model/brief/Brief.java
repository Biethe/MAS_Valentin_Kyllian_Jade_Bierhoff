package com.assistant.mail.model.brief;

import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.plan.InstructionType;

import java.util.ArrayList;
import java.util.List;

public class Brief {
    private InstructionType task;
    private List<String> to = new ArrayList<>();
    private List<String> cc = new ArrayList<>();
    private List<String> bcc = new ArrayList<>();
    private Language language;
    private String recipientRole;
    private DraftTone tone;
    private DraftStructure structure;
    private List<String> styleNotes = new ArrayList<>();
    private List<Information> facts = new ArrayList<>();
    private List<AttachmentFile> attachments = new ArrayList<>();
    private List<AttachmentFile> contextFiles = new ArrayList<>();
    private List<Email> emails = new ArrayList<>();
    private Long replyToId;
    private List<String> doNotFollow = new ArrayList<>();
    /** Background on the recipient and their profile (contact card, retrieved passages). Not part of the checklist. */
    private List<String> knowledge = new ArrayList<>();
    /** The instruction this brief serves (what the user asked). The summarizer uses it to keep the dates the user named. */
    private String focus = "";

    public Brief() {}

    public InstructionType getTask() { return task; }
    public void setTask(InstructionType task) { this.task = task; }

    public List<String> getTo() { return to; }
    public void setTo(List<String> to) { this.to = emptyIfNull(to); }

    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = emptyIfNull(cc); }

    public List<String> getBcc() { return bcc; }
    public void setBcc(List<String> bcc) { this.bcc = emptyIfNull(bcc); }

    public Language getLanguage() { return language; }
    public void setLanguage(Language language) { this.language = language; }

    public String getRecipientRole() { return recipientRole; }
    public void setRecipientRole(String recipientRole) { this.recipientRole = recipientRole; }

    public DraftTone getTone() { return tone; }
    public void setTone(DraftTone tone) { this.tone = tone; }

    public DraftStructure getStructure() { return structure; }
    public void setStructure(DraftStructure structure) { this.structure = structure; }

    public List<String> getStyleNotes() { return styleNotes; }
    public void setStyleNotes(List<String> styleNotes) { this.styleNotes = emptyIfNull(styleNotes); }

    public List<Information> getFacts() { return facts; }
    public void setFacts(List<Information> facts) { this.facts = emptyIfNull(facts); }

    public List<AttachmentFile> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentFile> attachments) { this.attachments = emptyIfNull(attachments); }

    public List<AttachmentFile> getContextFiles() { return contextFiles; }
    public void setContextFiles(List<AttachmentFile> contextFiles) { this.contextFiles = emptyIfNull(contextFiles); }

    public List<Email> getEmails() { return emails; }
    public void setEmails(List<Email> emails) { this.emails = emptyIfNull(emails); }

    public Long getReplyToId() { return replyToId; }
    public void setReplyToId(Long replyToId) { this.replyToId = replyToId; }

    public List<String> getDoNotFollow() { return doNotFollow; }
    public void setDoNotFollow(List<String> doNotFollow) { this.doNotFollow = emptyIfNull(doNotFollow); }

    public String getFocus() { return focus; }
    public void setFocus(String focus) { this.focus = focus == null ? "" : focus; }

    public List<String> getKnowledge() { return knowledge; }
    public void setKnowledge(List<String> knowledge) { this.knowledge = emptyIfNull(knowledge); }

    private static <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? new ArrayList<>() : values;
    }
}

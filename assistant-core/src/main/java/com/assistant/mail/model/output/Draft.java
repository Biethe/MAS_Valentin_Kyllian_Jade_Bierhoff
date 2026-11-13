package com.assistant.mail.model.output;

import com.assistant.mail.model.brief.DraftStructure;
import com.assistant.mail.model.brief.DraftTone;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.mail.AttachmentFile;

import java.util.ArrayList;
import java.util.List;

public class Draft {
    private Long id;
    private String subject;
    private String body;
    private List<String> recipients = new ArrayList<>();
    private List<String> cc = new ArrayList<>();
    private List<String> bcc = new ArrayList<>();
    private List<Information> information = new ArrayList<>();
    private List<AttachmentFile> attachments = new ArrayList<>();
    private DraftStructure structure;
    private DraftTone tone;

    public Draft() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<String> getRecipients() { return recipients; }
    public void setRecipients(List<String> recipients) { this.recipients = recipients; }

    public List<String> getCc() { return cc; }
    public void setCc(List<String> cc) { this.cc = cc; }

    public List<String> getBcc() { return bcc; }
    public void setBcc(List<String> bcc) { this.bcc = bcc; }

    public List<Information> getInformation() { return information; }
    public void setInformation(List<Information> information) { this.information = information; }

    public List<AttachmentFile> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentFile> attachments) { this.attachments = attachments; }

    public DraftStructure getStructure() { return structure; }
    public void setStructure(DraftStructure structure) { this.structure = structure; }

    public DraftTone getTone() { return tone; }
    public void setTone(DraftTone tone) { this.tone = tone; }
}

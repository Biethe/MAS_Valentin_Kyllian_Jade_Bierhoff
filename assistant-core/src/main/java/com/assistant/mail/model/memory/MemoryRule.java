package com.assistant.mail.model.memory;

import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.plan.InstructionType;

public class MemoryRule {
    private String text = "";
    private AgentKind agent;
    private InstructionType task;
    private BriefPart part;
    private String recipientRole;
    private MemoryOrigin origin;
    private String reason = "";
    private int helpful;
    private int harmful;
    private Boolean vetoed;

    public MemoryRule() {}

    public String getText() { return text; }
    public void setText(String text) { this.text = text == null ? "" : text; }

    public AgentKind getAgent() { return agent; }
    public void setAgent(AgentKind agent) { this.agent = agent; }

    public InstructionType getTask() { return task; }
    public void setTask(InstructionType task) { this.task = task; }

    public BriefPart getPart() { return part; }
    public void setPart(BriefPart part) { this.part = part; }

    public String getRecipientRole() { return recipientRole; }
    public void setRecipientRole(String recipientRole) { this.recipientRole = recipientRole; }

    public MemoryOrigin getOrigin() { return origin; }
    public void setOrigin(MemoryOrigin origin) { this.origin = origin; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason == null ? "" : reason; }

    public int getHelpful() { return helpful; }
    public void setHelpful(int helpful) { this.helpful = helpful; }

    public int getHarmful() { return harmful; }
    public void setHarmful(int harmful) { this.harmful = harmful; }

    public Boolean getVetoed() { return vetoed; }
    public void setVetoed(Boolean vetoed) { this.vetoed = vetoed; }
}

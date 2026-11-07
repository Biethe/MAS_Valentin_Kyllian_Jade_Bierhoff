package com.assistant.mail.model.memory;

import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.plan.InstructionType;

public class Dispute {
    private AgentKind agent;
    private InstructionType task;
    private BriefPart part;
    private String criticArgument = "";
    private String agentArgument = "";
    private Boolean criticRight;
    private String lesson = "";

    public Dispute() {}

    public AgentKind getAgent() { return agent; }
    public void setAgent(AgentKind agent) { this.agent = agent; }

    public InstructionType getTask() { return task; }
    public void setTask(InstructionType task) { this.task = task; }

    public BriefPart getPart() { return part; }
    public void setPart(BriefPart part) { this.part = part; }

    public String getCriticArgument() { return criticArgument; }
    public void setCriticArgument(String criticArgument) {
        this.criticArgument = criticArgument == null ? "" : criticArgument;
    }

    public String getAgentArgument() { return agentArgument; }
    public void setAgentArgument(String agentArgument) {
        this.agentArgument = agentArgument == null ? "" : agentArgument;
    }

    public Boolean getCriticRight() { return criticRight; }
    public void setCriticRight(Boolean criticRight) { this.criticRight = criticRight; }

    public String getLesson() { return lesson; }
    public void setLesson(String lesson) { this.lesson = lesson == null ? "" : lesson; }
}

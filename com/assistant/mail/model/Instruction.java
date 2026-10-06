package com.assistant.mail.model;

public class Instruction {
    private String body;
    private InstructionType type;
    private boolean atomic;
    private boolean clear;

    public Instruction() {}

    public Instruction(String body, InstructionType type, boolean atomic, boolean clear) {
        this.body = body;
        this.type = type;
        this.atomic = atomic;
        this.clear = clear;
    }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public InstructionType getType() { return type; }
    public void setType(InstructionType type) { this.type = type; }

    public boolean isAtomic() { return atomic; }
    public void setAtomic(boolean atomic) { this.atomic = atomic; }

    public boolean isClear() { return clear; }
    public void setClear(boolean clear) { this.clear = clear; }
}

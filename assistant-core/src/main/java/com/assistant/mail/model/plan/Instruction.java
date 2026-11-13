package com.assistant.mail.model.plan;

public class Instruction {
    private String body;
    private InstructionType type;
    private boolean isAtomic;
    private boolean isClear;

    public Instruction() {}

    public Instruction(String body, InstructionType type, boolean isAtomic, boolean isClear) {
        this.body = body;
        this.type = type;
        this.isAtomic = isAtomic;
        this.isClear = isClear;
    }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public InstructionType getType() { return type; }
    public void setType(InstructionType type) { this.type = type; }

    public boolean getIsAtomic() { return isAtomic; }
    public void setIsAtomic(boolean isAtomic) { this.isAtomic = isAtomic; }

    public boolean getIsClear() { return isClear; }
    public void setIsClear(boolean isClear) { this.isClear = isClear; }
}

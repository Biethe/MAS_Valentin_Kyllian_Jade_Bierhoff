package com.assistant.mail.model.memory;

import java.util.ArrayList;
import java.util.List;

public class ShortTermMemory {
    private List<Dispute> disputes = new ArrayList<>();

    public ShortTermMemory() {}

    public List<Dispute> getDisputes() { return disputes; }
    public void setDisputes(List<Dispute> disputes) {
        this.disputes = disputes == null ? new ArrayList<>() : disputes;
    }
}

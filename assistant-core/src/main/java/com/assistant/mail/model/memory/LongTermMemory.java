package com.assistant.mail.model.memory;

import java.util.ArrayList;
import java.util.List;

public class LongTermMemory {
    private List<MemoryRule> rules = new ArrayList<>();

    public LongTermMemory() {}

    public List<MemoryRule> getRules() { return rules; }
    public void setRules(List<MemoryRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }
}

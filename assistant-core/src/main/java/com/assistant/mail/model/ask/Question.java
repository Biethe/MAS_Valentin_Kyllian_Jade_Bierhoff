package com.assistant.mail.model.ask;

import java.util.ArrayList;
import java.util.List;

public class Question {
    private String text;
    private QuestionReason reason;
    private List<String> options = new ArrayList<>();

    public Question() {}

    public Question(String text, QuestionReason reason, List<String> options) {
        this.text = text;
        this.reason = reason;
        this.options = options == null ? new ArrayList<>() : options;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public QuestionReason getReason() { return reason; }
    public void setReason(QuestionReason reason) { this.reason = reason; }

    public List<String> getOptions() { return options; }
    public void setOptions(List<String> options) {
        this.options = options == null ? new ArrayList<>() : options;
    }
}

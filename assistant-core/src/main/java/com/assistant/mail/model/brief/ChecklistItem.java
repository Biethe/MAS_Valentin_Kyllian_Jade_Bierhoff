package com.assistant.mail.model.brief;

public class ChecklistItem {
    private BriefPart part;
    private String expected;
    private boolean checked;
    private String feedback = "";

    public ChecklistItem() {}

    public BriefPart getPart() { return part; }
    public void setPart(BriefPart part) { this.part = part; }

    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }

    public boolean isChecked() { return checked; }
    public void setChecked(boolean checked) { this.checked = checked; }

    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback == null ? "" : feedback; }
}

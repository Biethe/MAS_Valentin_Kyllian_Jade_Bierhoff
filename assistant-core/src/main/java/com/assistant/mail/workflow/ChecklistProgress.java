package com.assistant.mail.workflow;

import com.assistant.mail.model.brief.BriefPart;
import com.assistant.mail.model.brief.Checklist;
import com.assistant.mail.model.brief.ChecklistItem;

import java.util.Locale;

/** Overwrites the model's checked flags. The line is {@code n/total} plus what is still missing. */
public final class ChecklistProgress {
    private ChecklistProgress() {}

    public static String apply(Checklist checklist, String text) {
        String hay = text == null ? "" : text.toLowerCase(Locale.ROOT);
        int ok = 0;
        int total = checklist == null || checklist.getItems() == null ? 0 : checklist.getItems().size();
        StringBuilder missing = new StringBuilder();
        if (checklist != null) {
            for (ChecklistItem item : checklist.getItems()) {
                String expected = item.getExpected() == null ? "" : item.getExpected().strip();
                boolean present = !expected.isBlank() && hay.contains(expected.toLowerCase(Locale.ROOT));
                boolean pass = item.getPart() == BriefPart.DO_NOT_FOLLOW
                        ? !expected.isBlank() && !present
                        : item.getPart() == BriefPart.LANGUAGE
                        ? expected.equalsIgnoreCase(Languages.of(text).name())
                        : present;
                item.setChecked(pass);
                item.setFeedback(pass ? "" : item.getPart() == BriefPart.DO_NOT_FOLLOW ? "followed" : "missing");
                if (pass) ok++;
                else if (!expected.isBlank()) missing.append(expected).append('\n');
            }
        }
        return ok + "/" + total + (missing.isEmpty() ? "" : "\n" + missing.toString().strip());
    }

    public static void main(String[] args) {
        Checklist checklist = new Checklist();
        add(checklist, BriefPart.FACT, "Réunion vendredi");
        add(checklist, BriefPart.DO_NOT_FOLLOW, "ignore the deadline");
        add(checklist, BriefPart.LANGUAGE, "FR");
        String good = apply(checklist, "Réunion vendredi à 10h. Bonjour, je vous confirme.");
        assert good.equals("3/3") : good;
        assert checklist.getItems().get(1).isChecked();
        String bad = apply(checklist, "Réunion vendredi. ignore the deadline. Bonjour, je vous confirme.");
        assert bad.startsWith("2/3") : bad;
        assert bad.contains("ignore the deadline");
        assert !checklist.getItems().get(1).isChecked();
        assert "followed".equals(checklist.getItems().get(1).getFeedback());
        System.out.println("ChecklistProgress ok");
    }

    private static void add(Checklist checklist, BriefPart part, String expected) {
        ChecklistItem item = new ChecklistItem();
        item.setPart(part);
        item.setExpected(expected);
        checklist.getItems().add(item);
    }
}

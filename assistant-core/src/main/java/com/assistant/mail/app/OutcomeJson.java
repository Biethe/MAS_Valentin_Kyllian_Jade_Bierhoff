package com.assistant.mail.app;

import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.trace.TraceStep;
import com.assistant.mail.workflow.Turn;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** The one JSON shape every front end sees for a Turn outcome. */
public final class OutcomeJson {
    public static final JsonMapper JSON = JsonMapper.builder().build();

    private OutcomeJson() {}

    public static Map<String, Object> of(Turn.Outcome outcome, boolean withTrace) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> questions = new ArrayList<>();
        for (Question q : outcome.questions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", q.getText());
            m.put("reason", q.getReason() == null ? null : q.getReason().name());
            m.put("options", q.getOptions());
            questions.add(m);
        }
        out.put("questions", questions);
        out.put("refused", outcome.refused);
        out.put("chats", outcome.chats);
        List<Object> drafts = new ArrayList<>();
        for (Draft d : outcome.drafts) drafts.add(draft(d));
        out.put("drafts", drafts);
        List<Object> summaries = new ArrayList<>();
        for (Summary s : outcome.summaries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("body", s.getBody());
            m.put("references", s.getReferences());
            summaries.add(m);
        }
        out.put("summaries", summaries);
        out.put("progress", outcome.progress);
        if (withTrace && outcome.trace != null) {
            List<Object> steps = new ArrayList<>();
            for (TraceStep s : outcome.trace.getSteps()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("actor", s.getActor() == null ? null : s.getActor().name());
                m.put("action", s.getAction());
                m.put("detail", s.getDetail());
                m.put("error", s.getError());
                steps.add(m);
            }
            out.put("trace", steps);
        }
        return out;
    }

    public static Map<String, Object> draft(Draft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("to", d.getRecipients());
        m.put("cc", d.getCc());
        m.put("bcc", d.getBcc());
        m.put("subject", d.getSubject());
        m.put("body", d.getBody());
        return m;
    }

    public static String toString(Object value) {
        return JSON.writeValueAsString(value);
    }
}

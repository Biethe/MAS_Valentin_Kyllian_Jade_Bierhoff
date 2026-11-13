package com.assistant.mail.llm;

import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import java.util.Locale;

/**
 * Offline stand-in for the model, for demos and tests: it answers each prompt with a fixed,
 * well-formed block about one meeting mail. It proves the wiring, not the quality of a real model.
 */
public final class ScriptedModels implements PlannerAgentClient, DrafterAgentClient, SummarizerAgentClient {
    public static final String RECIPIENT = "ada@u.test";
    public static final String FACT = "Réunion vendredi à 10h.";

    @Override
    public String complete(String system, String user) {
        String lower = user == null ? "" : user.toLowerCase(Locale.ROOT);
        if (PlannerSystemPrompt.SPLIT.equals(system)) return "body:\n" + user.strip();
        if (PlannerSystemPrompt.ROUTE.equals(system)) {
            if (lower.contains("bonjour") || lower.contains("hello")) return "type:\nCHAT\n\nclear:\nyes";
            if (lower.contains("résume") || lower.contains("resume") || lower.contains("summar")) {
                return "type:\nSUMMARIZE\n\nclear:\nyes";
            }
            if (lower.contains("répond") || lower.contains("reponds") || lower.contains("reply")) {
                return "type:\nREPLY\n\nclear:\nyes";
            }
            return "type:\nREPLY\n\nclear:\nno";
        }
        if (PlannerSystemPrompt.ANSWER.equals(system)) {
            return "Bonjour ! Demandez-moi de résumer un mail ou d'y répondre.";
        }
        if (PlannerSystemPrompt.ASK_USER.equals(system)) return "Pouvez-vous préciser votre demande ?";
        if (PlannerSystemPrompt.TOOLS.equals(system)) {
            return user.contains("email:") ? "done:\nyes" : "name:\nsearch_mail\n\nquery:\nAda";
        }
        if (PlannerSystemPrompt.LOCATE.equals(system)) return "email:\n1";
        if (PlannerSystemPrompt.READ.equals(system)) return "body:\n" + FACT + "\n\nkind:\nINFORMATIVE";
        if (PlannerSystemPrompt.BRIEF.equals(system)) return "to:\n" + RECIPIENT + "\n\nfact:\n" + FACT;
        if (PlannerSystemPrompt.CRITIC.equals(system)) return "";
        if (PlannerSystemPrompt.CHECKLIST.equals(system)) {
            String fact = "part:\nFACT\n\nexpected:\n" + FACT + "\n";
            return user.contains("SUMMARIZE") ? fact : "part:\nTO\n\nexpected:\n" + RECIPIENT + "\n\n" + fact;
        }
        if (DrafterSystemPrompt.TEXT.equals(system) || DrafterSystemPrompt.REWRITE.equals(system)) {
            return "recipients:\n" + RECIPIENT + "\n\ncc:\n\nbcc:\n\nsubject:\nRe: RDV\n\nbody:\nBonjour Ada,\n\n"
                    + "Je confirme : " + FACT + "\n\nCordialement";
        }
        if (SummarizerSystemPrompt.TEXT.equals(system)) return FACT;
        if (DrafterSystemPrompt.MARK.equals(system) || DrafterSystemPrompt.CRITIC.equals(system)
                || SummarizerSystemPrompt.MARK.equals(system) || SummarizerSystemPrompt.CRITIC.equals(system)) {
            return "";
        }
        throw new IllegalStateException("scripted model has no answer for this prompt");
    }
}

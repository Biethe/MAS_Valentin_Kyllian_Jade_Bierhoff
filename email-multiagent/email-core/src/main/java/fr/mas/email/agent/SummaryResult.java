package fr.mas.email.agent;

/** The result of summarizing an email. */
public record SummaryResult(String summary, boolean needsReply, String draft) {
}

package fr.mas.email.routing;

import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMException;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based router. It asks the model to classify the email and falls back to
 * a deterministic strategy when the model is unavailable, returns an invalid
 * category, or is not confident enough.
 */
public final class LLMRouterStrategy implements RouterStrategy {

    private static final String SYSTEM_PROMPT = """
            You are an email router. Classify each email into one of:
            STUDENT, TEACHER, ADMINISTRATION, RESEARCH, GENERIC, SPAM, NO_REPLY, IMPORTANT.

            Examples:
            Email: "What is the deadline for the assignment?" -> STUDENT|0.9|course assignment
            Email: "Can we meet with the teaching team?" -> TEACHER|0.9|colleague meeting
            Email: "Please send the invoice for the expense claim." -> ADMINISTRATION|0.9|invoice
            Email: "The SuperMASProject milestone is approaching." -> RESEARCH|0.9|project
            Email: "Unsubscribe from this promotion" -> SPAM|0.9|promotion
            Email: "This is an automated notification" -> NO_REPLY|0.9|notification
            Email: "Hello, I have a quick question." -> GENERIC|0.8|ambiguous

            Return ONE line only: CATEGORY|confidence|reason. No other text.
            """;

    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "\\b(STUDENT|TEACHER|ADMINISTRATION|RESEARCH|GENERIC|SPAM|NO_REPLY|IMPORTANT)\\b\\s*\\|\\s*([0-9]*\\.?[0-9]+)\\s*(?:\\|\\s*(.*))?",
            Pattern.CASE_INSENSITIVE);

    private final LLMClient llmClient;
    private final RouterStrategy fallback;
    private final double confidenceThreshold;

    public LLMRouterStrategy(LLMClient llmClient, RouterStrategy fallback, double confidenceThreshold) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public RoutingDecision route(EmailMessage email) {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user("Subject: " + email.getSubject() + "\n\n" + email.getBody()));
        try {
            RoutingDecision decision = parse(llmClient.complete(messages));
            if (decision != null && decision.getConfidence() >= confidenceThreshold) {
                return decision;
            }
        } catch (LLMException e) {
            // Fall through to the deterministic strategy.
        }
        return fallback.route(email);
    }

    private RoutingDecision parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DECISION_PATTERN.matcher(raw);
        if (matcher.find()) {
            EmailCategory category = EmailCategory.valueOf(matcher.group(1).toUpperCase(Locale.ROOT));
            double confidence = 0.5;
            try {
                confidence = Double.parseDouble(matcher.group(2));
            } catch (NumberFormatException ignored) {
                confidence = 0.5;
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            String reason = matcher.group(3) == null ? "LLM classification" : matcher.group(3).trim();
            return new RoutingDecision(category, confidence, reason);
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        for (EmailCategory category : EmailCategory.values()) {
            if (upper.contains(category.name())) {
                return new RoutingDecision(category, 0.5, "LLM category without confidence");
            }
        }
        return null;
    }
}

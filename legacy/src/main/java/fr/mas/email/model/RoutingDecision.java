package fr.mas.email.model;

/**
 * Decision de routage prise par le coordinateur.
 */
public record RoutingDecision(
        String targetAgentId,
        EmailCategory category,
        double confidence,
        String rationale) {

    public static RoutingDecision fallback(double confidence, String rationale) {
        return new RoutingDecision(
                EmailCategory.GENERIC.agentId(), EmailCategory.GENERIC, confidence, rationale);
    }
}

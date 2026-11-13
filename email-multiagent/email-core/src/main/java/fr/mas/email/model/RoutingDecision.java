package fr.mas.email.model;

import java.util.Objects;

/** Result of the routing decision. */
public final class RoutingDecision {

    private final EmailCategory category;
    private final double confidence;
    private final String reason;

    public RoutingDecision(EmailCategory category, double confidence, String reason) {
        this.category = Objects.requireNonNull(category, "category");
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    public EmailCategory getCategory() {
        return category;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return "RoutingDecision{category=" + category + ", confidence=" + confidence + ", reason='" + reason + "'}";
    }
}

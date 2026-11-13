package fr.mas.email.routing;

import fr.mas.email.feedback.FeedbackStore;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

import java.util.Objects;

/**
 * Feedback-aware router. It keeps the delegate's category but lowers the
 * confidence when the category has been rejected often by the user. This
 * makes the hybrid strategy fall back to the LLM router for weak categories.
 */
public final class FeedbackRouter implements RouterStrategy {

    private final RouterStrategy delegate;
    private final FeedbackStore feedback;

    public FeedbackRouter(RouterStrategy delegate, FeedbackStore feedback) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
    }

    @Override
    public RoutingDecision route(EmailMessage email) {
        RoutingDecision decision = delegate.route(email);
        double rejection = feedback.rejectionRate(decision.getCategory().name());
        if (rejection >= 0.5) {
            double confidence = Math.max(0.0, decision.getConfidence() - 0.2);
            return new RoutingDecision(
                    decision.getCategory(),
                    confidence,
                    decision.getReason() + "; feedback rejection " + Math.round(rejection * 100) + "%");
        }
        return decision;
    }
}

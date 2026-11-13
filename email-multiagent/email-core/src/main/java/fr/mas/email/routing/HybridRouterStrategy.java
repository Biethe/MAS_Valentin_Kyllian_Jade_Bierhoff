package fr.mas.email.routing;

import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

import java.util.Objects;

/**
 * Keyword-first hybrid: the deterministic router is used when it is confident,
 * otherwise the LLM router takes over. The LLM router itself falls back to the
 * keyword router when the model is unavailable or uncertain.
 */
public final class HybridRouterStrategy implements RouterStrategy {

    private final RouterStrategy keywordRouter;
    private final RouterStrategy llmRouter;
    private final double keywordConfidence;

    public HybridRouterStrategy(RouterStrategy keywordRouter,
                                RouterStrategy llmRouter,
                                double keywordConfidence) {
        this.keywordRouter = Objects.requireNonNull(keywordRouter, "keywordRouter");
        this.llmRouter = Objects.requireNonNull(llmRouter, "llmRouter");
        this.keywordConfidence = keywordConfidence;
    }

    @Override
    public RoutingDecision route(EmailMessage email) {
        RoutingDecision keywordDecision = keywordRouter.route(email);
        if (keywordDecision.getCategory() != EmailCategory.GENERIC
                && keywordDecision.getConfidence() >= keywordConfidence) {
            return keywordDecision;
        }
        return llmRouter.route(email);
    }
}

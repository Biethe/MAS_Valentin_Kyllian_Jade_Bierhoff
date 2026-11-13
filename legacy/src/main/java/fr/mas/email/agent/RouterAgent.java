package fr.mas.email.agent;

import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;
import fr.mas.email.routing.RoutedResponse;
import fr.mas.email.routing.RouterStrategy;

/**
 * Agent coordinateur (router / dispatcher).
 *
 * <p>Il ne redige pas lui-meme : il choisit un agent specialise via une
 * {@link RouterStrategy}, lui delegue la tâche, puis renvoie la proposition.
 * En cas de confiance insuffisante, l'agent generique sert de repli.</p>
 */
public final class RouterAgent {

    public static final String ID = "router";

    private final AgentRegistry registry;
    private final RouterStrategy strategy;
    private final double confidenceThreshold;
    private final int maxHistoryMessages;

    public RouterAgent(AgentRegistry registry,
                       RouterStrategy strategy,
                       double confidenceThreshold,
                       int maxHistoryMessages) {
        this.registry = registry;
        this.strategy = strategy;
        this.confidenceThreshold = confidenceThreshold;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public RoutingDecision route(EmailMessage email, ConversationMemory memory) {
        RoutingDecision decision = strategy.route(email, registry, memory, maxHistoryMessages);
        if (decision.confidence() < confidenceThreshold) {
            return new RoutingDecision(
                    registry.fallback().id(),
                    registry.fallback().category(),
                    decision.confidence(),
                    decision.rationale() + " | confiance < seuil " + confidenceThreshold
                            + " -> repli sur '" + registry.fallback().id() + "'");
        }
        return decision;
    }

    public RoutedResponse process(EmailMessage email, ConversationMemory memory) {
        RoutingDecision decision = route(email, memory);
        Agent agent = resolve(decision);
        return new RoutedResponse(decision, agent.handle(email, memory));
    }

    public RoutedResponse regenerate(EmailMessage email, ConversationMemory memory, String userComment) {
        RoutingDecision decision = route(email, memory);
        Agent agent = resolve(decision);
        return new RoutedResponse(decision, agent.handle(email, memory, userComment));
    }

    private Agent resolve(RoutingDecision decision) {
        return registry.find(decision.targetAgentId()).orElseGet(registry::fallback);
    }
}

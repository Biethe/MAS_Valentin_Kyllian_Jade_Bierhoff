package fr.mas.email.agent;

import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;
import fr.mas.email.routing.RouterStrategy;

import java.util.Objects;

/** Coordinates the system: routes the email and delegates to the right agent. */
public final class RouterAgent implements Agent {

    private final String name;
    private final RouterStrategy routerStrategy;
    private final AgentRegistry registry;

    public RouterAgent(String name, RouterStrategy routerStrategy, AgentRegistry registry) {
        this.name = name;
        this.routerStrategy = Objects.requireNonNull(routerStrategy, "routerStrategy");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String category) {
        return false;
    }

    public RoutingDecision route(EmailMessage email) {
        return routerStrategy.route(email);
    }

    public Agent delegate(EmailCategory category) {
        return registry.find(category.name());
    }

    @Override
    public fr.mas.email.model.AgentResponse handle(EmailMessage email, java.util.List<String> conversationHistory) {
        throw new UnsupportedOperationException("The router does not draft responses");
    }
}

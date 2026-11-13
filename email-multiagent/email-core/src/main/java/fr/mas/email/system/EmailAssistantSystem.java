package fr.mas.email.system;

import fr.mas.email.agent.Agent;
import fr.mas.email.agent.RouterAgent;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.RoutingDecision;
import fr.mas.email.security.SecurityAgent;
import fr.mas.email.security.SecurityResult;

import java.util.List;
import java.util.Objects;

/** Orchestrates the email-assistance flow. */
public final class EmailAssistantSystem {

    private final RouterAgent router;
    private final ConversationMemory memory;
    private final String fallbackAgentName;
    private final SecurityAgent securityAgent;

    public EmailAssistantSystem(RouterAgent router, ConversationMemory memory, String fallbackAgentName) {
        this(router, memory, fallbackAgentName, null);
    }

    public EmailAssistantSystem(RouterAgent router,
                                ConversationMemory memory,
                                String fallbackAgentName,
                                SecurityAgent securityAgent) {
        this.router = Objects.requireNonNull(router, "router");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.fallbackAgentName = fallbackAgentName;
        this.securityAgent = securityAgent;
    }

    public RoutingDecision route(EmailMessage email) {
        return router.route(email);
    }

    public RoutedResponse handle(EmailMessage email) {
        SecurityResult security = securityAgent == null ? SecurityResult.safe() : securityAgent.check(email);
        if (security.suspicious()) {
            RoutingDecision decision = new RoutingDecision(EmailCategory.IMPORTANT, 0.0, "quarantined: " + security.reason());
            AgentResponse response = new AgentResponse("SecurityAgent", EmailCategory.IMPORTANT, "Quarantined: " + security.reason());
            return new RoutedResponse(decision, response, security);
        }

        RoutingDecision decision = router.route(email);
        if (decision.getCategory() == EmailCategory.SPAM
                || decision.getCategory() == EmailCategory.NO_REPLY) {
            AgentResponse response = new AgentResponse(
                    "GenericAgent",
                    decision.getCategory(),
                    "No reply needed: this message is classified as " + decision.getCategory().name().toLowerCase() + ".");
            return new RoutedResponse(decision, response, security);
        }
        Agent agent = router.delegate(decision.getCategory());
        if (agent == null) {
            agent = router.delegate(EmailCategory.GENERIC);
        }
        if (agent == null) {
            throw new IllegalStateException("No agent for " + decision.getCategory());
        }

        List<String> history = memory.history(email.getThreadId());
        AgentResponse response = agent.handle(email, history);
        memory.append(email.getThreadId(), response.getContent());
        return new RoutedResponse(decision, response, security);
    }

    public static final class RoutedResponse {

        private final RoutingDecision decision;
        private final AgentResponse response;
        private final SecurityResult security;

        public RoutedResponse(RoutingDecision decision, AgentResponse response) {
            this(decision, response, SecurityResult.safe());
        }

        public RoutedResponse(RoutingDecision decision, AgentResponse response, SecurityResult security) {
            this.decision = decision;
            this.response = response;
            this.security = security;
        }

        public RoutingDecision getDecision() {
            return decision;
        }

        public AgentResponse getResponse() {
            return response;
        }

        public SecurityResult getSecurity() {
            return security;
        }

        public boolean isQuarantined() {
            return security.suspicious();
        }
    }
}

package fr.mas.email;

import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.agent.RouterAgent;
import fr.mas.email.config.AppConfig;
import fr.mas.email.llm.MockLLMClient;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.SenderRole;
import fr.mas.email.routing.KeywordRouterStrategy;
import fr.mas.email.system.EmailAssistantSystem;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailAssistantSystemTest {

    private EmailMessage email(String from, SenderRole role, String subject, String body) {
        return new EmailMessage(from, "Test", subject, body, Instant.now(), null, role);
    }

    @Test
    void keywordRouterRoutesStudentEmailToStudentAgent() {
        EmailMessage message = email("a@univ.fr", SenderRole.UNKNOWN,
                "Question sur le cours", "Bonjour, une question sur les notes du TD.");
        var decision = new KeywordRouterStrategy().route(message, new AgentRegistry(),
                new ConversationMemory(), 10);
        assertEquals("student", decision.targetAgentId());
    }

    @Test
    void registryContainsFallbackAndSpecialisedAgents() {
        System.setProperty("llm.provider", "mock");
        AppConfig config = AppConfig.load();
        EmailAssistantSystem system = new EmailAssistantSystem(config);
        assertEquals(4, system.registry().size());
        assertTrue(system.registry().find("generic").isPresent());
        assertTrue(system.registry().find("student").isPresent());
    }

    @Test
    void routerFallsBackWhenConfidenceIsLow() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(new fr.mas.email.agent.specialized.GenericAgent(
                new MockLLMClient(), "prompt", e -> "", 10));
        RouterAgent router = new RouterAgent(registry,
                (e, r, m, h) -> fr.mas.email.model.RoutingDecision.fallback(0.1, "incertain"),
                0.35, 10);
        var decision = router.route(email("x@y.z", SenderRole.UNKNOWN, "s", "b"),
                new ConversationMemory());
        assertEquals("generic", decision.targetAgentId());
    }

    @Test
    void systemProducesProposalWithMockLlm() {
        System.setProperty("llm.provider", "mock");
        EmailAssistantSystem system = new EmailAssistantSystem(AppConfig.load());
        var routed = system.process(email("etudiant@univ.fr", SenderRole.STUDENT,
                "Question cours", "Bonjour, une question sur le cours."));
        assertFalse(routed.response().content().isBlank());
        assertEquals("student", routed.decision().targetAgentId());
    }
}

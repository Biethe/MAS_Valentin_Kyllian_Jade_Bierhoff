package fr.mas.email;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.agent.RouterAgent;
import fr.mas.email.context.FileContextProvider;
import fr.mas.email.llm.MockLLMClient;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;
import fr.mas.email.routing.KeywordRouterStrategy;
import fr.mas.email.system.EmailAssistantSystem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EmailAssistantSystemTest {

    private EmailAssistantSystem newSystem() {
        AgentRegistry registry = AgentFactory.createDefaultRegistry(
                new MockLLMClient(),
                new FileContextProvider(Path.of("context")),
                new PromptRepository());
        RouterAgent router = new RouterAgent("RouterAgent", new KeywordRouterStrategy(0.5), registry);
        return new EmailAssistantSystem(router, new ConversationMemory(10), "GenericAgent");
    }

    @Test
    void routesStudentEmailToStudentAgent() {
        EmailMessage email = new EmailMessage("student@uni.fr", "teacher@uni.fr",
                "Question about the course", "What is the deadline for the assignment?", "t1");
        EmailAssistantSystem.RoutedResponse response = newSystem().handle(email);
        assertEquals(EmailCategory.STUDENT, response.getDecision().getCategory());
        assertEquals("StudentAgent", response.getResponse().getAgentName());
        assertNotNull(response.getResponse().getContent());
    }

    @Test
    void fallsBackToGenericAgentForAmbiguousEmail() {
        EmailMessage email = new EmailMessage("someone@uni.fr", "teacher@uni.fr",
                "Hello", "I have a quick question.", "t2");
        EmailAssistantSystem.RoutedResponse response = newSystem().handle(email);
        assertEquals(EmailCategory.GENERIC, response.getDecision().getCategory());
        assertEquals("GenericAgent", response.getResponse().getAgentName());
    }
}

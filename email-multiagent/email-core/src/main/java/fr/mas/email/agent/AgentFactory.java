package fr.mas.email.agent;

import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.agent.specialized.AdministrationAgent;
import fr.mas.email.agent.specialized.GenericAgent;
import fr.mas.email.agent.specialized.ResearchAgent;
import fr.mas.email.agent.specialized.StudentAgent;
import fr.mas.email.agent.specialized.TeacherAgent;
import fr.mas.email.prompt.PromptRepository;

/** Builds the default agent organization. */
public final class AgentFactory {

    private AgentFactory() {
    }

    public static AgentRegistry createDefaultRegistry(LLMClient llmClient,
                                                      ContextProvider contextProvider,
                                                      PromptRepository promptRepository) {
        return createDefaultRegistry(llmClient, contextProvider, promptRepository, null);
    }

    public static AgentRegistry createDefaultRegistry(LLMClient llmClient,
                                                      ContextProvider contextProvider,
                                                      PromptRepository promptRepository,
                                                      RecipientKnowledgeBase knowledgeBase) {
        AgentRegistry registry = new AgentRegistry();
        registry.register(new StudentAgent(llmClient, contextProvider, promptRepository, knowledgeBase));
        registry.register(new TeacherAgent(llmClient, contextProvider, promptRepository, knowledgeBase));
        registry.register(new AdministrationAgent(llmClient, contextProvider, promptRepository, knowledgeBase));
        registry.register(new ResearchAgent(llmClient, contextProvider, promptRepository, knowledgeBase));
        registry.register(new GenericAgent(llmClient, contextProvider, promptRepository, knowledgeBase));
        return registry;
    }
}

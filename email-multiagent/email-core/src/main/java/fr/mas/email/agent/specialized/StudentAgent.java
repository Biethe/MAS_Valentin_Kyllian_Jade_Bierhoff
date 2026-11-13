package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.prompt.PromptRepository;

public final class StudentAgent extends AbstractAgent {

    public StudentAgent(LLMClient llmClient, ContextProvider contextProvider, PromptRepository promptRepository) {
        this(llmClient, contextProvider, promptRepository, null);
    }

    public StudentAgent(LLMClient llmClient,
                        ContextProvider contextProvider,
                        PromptRepository promptRepository,
                        RecipientKnowledgeBase knowledgeBase) {
        super("StudentAgent", EmailCategory.STUDENT, "student",
                llmClient, contextProvider, promptRepository, knowledgeBase);
    }
}

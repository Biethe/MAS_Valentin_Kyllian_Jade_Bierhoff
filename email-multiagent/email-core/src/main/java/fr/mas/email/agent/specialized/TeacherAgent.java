package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.prompt.PromptRepository;

public final class TeacherAgent extends AbstractAgent {

    public TeacherAgent(LLMClient llmClient, ContextProvider contextProvider, PromptRepository promptRepository) {
        this(llmClient, contextProvider, promptRepository, null);
    }

    public TeacherAgent(LLMClient llmClient,
                        ContextProvider contextProvider,
                        PromptRepository promptRepository,
                        RecipientKnowledgeBase knowledgeBase) {
        super("TeacherAgent", EmailCategory.TEACHER, "teacher",
                llmClient, contextProvider, promptRepository, knowledgeBase);
    }
}

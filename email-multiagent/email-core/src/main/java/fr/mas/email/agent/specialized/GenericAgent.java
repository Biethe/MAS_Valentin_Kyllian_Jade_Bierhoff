package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.prompt.PromptRepository;

/**
 * Fallback agent. It handles the generic category and every category that has
 * no dedicated specialist (spam, no-reply, important, ...).
 */
public final class GenericAgent extends AbstractAgent {

    public GenericAgent(LLMClient llmClient, ContextProvider contextProvider, PromptRepository promptRepository) {
        this(llmClient, contextProvider, promptRepository, null);
    }

    public GenericAgent(LLMClient llmClient,
                        ContextProvider contextProvider,
                        PromptRepository promptRepository,
                        RecipientKnowledgeBase knowledgeBase) {
        super("GenericAgent", EmailCategory.GENERIC, "generic",
                llmClient, contextProvider, promptRepository, knowledgeBase);
    }

    @Override
    public boolean supports(String categoryName) {
        return true;
    }
}

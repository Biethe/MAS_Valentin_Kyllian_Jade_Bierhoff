package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;

/**
 * Agent de repli pour les emails ambigus ou hors categories specialisees.
 */
public final class GenericAgent extends AbstractAgent {

    public static final String ID = "generic";

    public GenericAgent(LLMClient llmClient, String systemPrompt,
                        ContextProvider contextProvider, int maxHistoryMessages) {
        super(ID, EmailCategory.GENERIC,
                "Emails ambigus ou hors categories specialisees (repli prudent).",
                llmClient, systemPrompt, contextProvider, maxHistoryMessages);
    }
}

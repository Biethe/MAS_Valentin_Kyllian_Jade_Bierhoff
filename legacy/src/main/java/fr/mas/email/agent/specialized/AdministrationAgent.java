package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;

/**
 * Agent dedie aux echanges administratifs.
 */
public final class AdministrationAgent extends AbstractAgent {

    public static final String ID = "administration";

    public AdministrationAgent(LLMClient llmClient, String systemPrompt,
                              ContextProvider contextProvider, int maxHistoryMessages) {
        super(ID, EmailCategory.ADMINISTRATION,
                "Demandes administratives : contrats, missions, factures, RH, scolarite.",
                llmClient, systemPrompt, contextProvider, maxHistoryMessages);
    }
}

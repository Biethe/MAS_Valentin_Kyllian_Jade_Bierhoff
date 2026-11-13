package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;

/**
 * Agent dedie aux echanges avec les etudiants.
 */
public final class StudentAgent extends AbstractAgent {

    public static final String ID = "student";

    public StudentAgent(LLMClient llmClient, String systemPrompt,
                        ContextProvider contextProvider, int maxHistoryMessages) {
        super(ID, EmailCategory.STUDENT,
                "Questions d'etudiants : cours, notes, stages, soutenances, inscriptions.",
                llmClient, systemPrompt, contextProvider, maxHistoryMessages);
    }
}

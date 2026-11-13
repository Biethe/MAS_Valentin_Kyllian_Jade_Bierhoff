package fr.mas.email.agent.specialized;

import fr.mas.email.agent.AbstractAgent;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;

/**
 * Agent dedie aux echanges entre enseignants et avec les collegues.
 */
public final class TeacherAgent extends AbstractAgent {

    public static final String ID = "teacher";

    public TeacherAgent(LLMClient llmClient, String systemPrompt,
                        ContextProvider contextProvider, int maxHistoryMessages) {
        super(ID, EmailCategory.TEACHER,
                "Echanges entre enseignants : jurys, cours, coordination, recherche.",
                llmClient, systemPrompt, contextProvider, maxHistoryMessages);
    }
}

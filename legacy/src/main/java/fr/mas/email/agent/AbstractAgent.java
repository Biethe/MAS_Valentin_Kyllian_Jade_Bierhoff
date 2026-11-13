package fr.mas.email.agent;

import fr.mas.email.context.ContextProvider;
import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;

/**
 * Base commune des agents (pattern <em>Template Method</em>).
 *
 * <p>{@link #handle(EmailMessage, ConversationMemory)} fixe le squelette de
 * generation : collecte du contexte, de l'historique, appel au LLM, packaging.
 * Les sous-classes se contentent de fournir identifiant, categorie et
 * description ; le comportement reste configurable via le prompt et le
 * contexte externes.</p>
 */
public abstract class AbstractAgent implements Agent {

    private final String id;
    private final EmailCategory category;
    private final String description;
    private final LLMClient llmClient;
    private final String systemPrompt;
    private final ContextProvider contextProvider;
    private final int maxHistoryMessages;

    protected AbstractAgent(String id,
                            EmailCategory category,
                            String description,
                            LLMClient llmClient,
                            String systemPrompt,
                            ContextProvider contextProvider,
                            int maxHistoryMessages) {
        this.id = id;
        this.category = category;
        this.description = description;
        this.llmClient = llmClient;
        this.systemPrompt = systemPrompt;
        this.contextProvider = contextProvider;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final EmailCategory category() {
        return category;
    }

    @Override
    public final String description() {
        return description;
    }

    @Override
    public final AgentResponse handle(EmailMessage email, ConversationMemory memory) {
        return handle(email, memory, null);
    }

    @Override
    public final AgentResponse handle(EmailMessage email, ConversationMemory memory, String userComment) {
        String userPrompt = buildUserPrompt(email, memory, userComment);
        String content = llmClient.complete(systemPrompt, userPrompt);
        return new AgentResponse(id, category, content.strip(), 1.0,
                "Proposition generee par l'agent '" + id + "' (" + llmClient.modelName() + ")");
    }

    protected final String systemPrompt() {
        return systemPrompt;
    }

    protected String buildUserPrompt(EmailMessage email, ConversationMemory memory, String userComment) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("EMAIL A TRAITER\n==============\n")
                .append(email.toPromptText()).append("\n\n");

        String history = memory.format(email.threadId(), maxHistoryMessages);
        if (!history.isBlank()) {
            prompt.append("HISTORIQUE DU FIL DE DISCUSSION\n===============================\n")
                    .append(history).append("\n");
        }

        String context = contextProvider.provideContext(email);
        if (context != null && !context.isBlank()) {
            prompt.append("CONTEXTE DOCUMENTAIRE DE L'AGENT\n===============================\n")
                    .append(context).append("\n");
        }

        if (userComment != null && !userComment.isBlank()) {
            prompt.append("RETOUR DE L'UTILISATEUR SUR LA PROPOSITION PRECEDENTE\n")
                    .append("=====================================================\n")
                    .append(userComment).append("\n\n");
        }

        prompt.append("CONSIGNE\n========\n")
                .append("Redige une proposition de reponse a cet email, prete a etre envoyee, ")
                .append("en respectant strictement ton role, ton ton et les regles ci-dessus. ")
                .append("Ne recopie pas ces instructions. Reponds directement par le corps de l'email.")
                .append(userComment != null && !userComment.isBlank()
                        ? " Tiens compte du retour de l'utilisateur." : "");
        return prompt.toString();
    }
}

package fr.mas.email.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.mas.email.agent.Agent;
import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

/**
 * Routage par LLM avec repli heuristique.
 *
 * <p>Construit un prompt de classification listant les agents disponibles et
 * demande une decision au format JSON. Toute erreur ou reponse invalide
 * declenche la strategie de repli.</p>
 */
public final class LlmRouterStrategy implements RouterStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LLMClient llmClient;
    private final String routerPrompt;
    private final RouterStrategy fallback;

    public LlmRouterStrategy(LLMClient llmClient, String routerPrompt, RouterStrategy fallback) {
        this.llmClient = llmClient;
        this.routerPrompt = routerPrompt;
        this.fallback = fallback;
    }

    @Override
    public RoutingDecision route(EmailMessage email, AgentRegistry registry,
                                 ConversationMemory memory, int maxHistoryMessages) {
        String userPrompt = buildUserPrompt(email, registry, memory, maxHistoryMessages);
        try {
            String raw = llmClient.complete(routerPrompt, userPrompt);
            return parse(raw);
        } catch (RuntimeException e) {
            return fallback.route(email, registry, memory, maxHistoryMessages);
        }
    }

    private String buildUserPrompt(EmailMessage email, AgentRegistry registry,
                                   ConversationMemory memory, int maxHistoryMessages) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("AGENTS DISPONIBLES\n==================\n");
        for (Agent agent : registry.all()) {
            prompt.append("- ").append(agent.id()).append(" : ").append(agent.description()).append("\n");
        }

        String history = memory.format(email.threadId(), maxHistoryMessages);
        if (!history.isBlank()) {
            prompt.append("\nHISTORIQUE DU FIL\n=================\n").append(history);
        }

        prompt.append("\nEMAIL A CLASSER\n===============\n").append(email.toPromptText());
        prompt.append("\n\nRends uniquement le JSON de decision.");
        return prompt.toString();
    }

    private RoutingDecision parse(String raw) {
        try {
            String json = extractJsonObject(raw);
            JsonNode node = MAPPER.readTree(json);
            String agentId = node.path("agent").asText("generic");
            double confidence = node.path("confidence").asDouble(0.0);
            String rationale = node.path("rationale").asText("Decision du routeur LLM");

            EmailCategory category = EmailCategory.fromAgentId(agentId);
            return new RoutingDecision(category.agentId(), category, confidence, rationale);
        } catch (Exception e) {
            throw new IllegalArgumentException("Reponse de routage invalide : " + raw, e);
        }
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Reponse de routage sans JSON : " + raw);
        }
        return raw.substring(start, end + 1);
    }
}

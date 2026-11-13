package fr.mas.email.model;

/**
 * Reponse produite par un agent, avant validation par l'utilisateur.
 */
public record AgentResponse(
        String agentId,
        EmailCategory category,
        String content,
        double confidence,
        String rationale) {

    public AgentResponse withContent(String newContent) {
        return new AgentResponse(agentId, category, newContent, confidence, rationale);
    }
}

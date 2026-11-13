package fr.mas.email.model;

import java.util.Objects;

/** A response proposed by a specialized agent. */
public final class AgentResponse {

    private final String agentName;
    private final EmailCategory category;
    private final String content;

    public AgentResponse(String agentName, EmailCategory category, String content) {
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.category = Objects.requireNonNull(category, "category");
        this.content = Objects.requireNonNull(content, "content");
    }

    public String getAgentName() {
        return agentName;
    }

    public EmailCategory getCategory() {
        return category;
    }

    public String getContent() {
        return content;
    }
}

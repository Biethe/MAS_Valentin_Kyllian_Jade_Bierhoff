package fr.mas.email.model;

/**
 * Categories d'emails prises en charge par les agents specialises.
 */
public enum EmailCategory {
    STUDENT("student"),
    TEACHER("teacher"),
    ADMINISTRATION("administration"),
    GENERIC("generic");

    private final String agentId;

    EmailCategory(String agentId) {
        this.agentId = agentId;
    }

    public String agentId() {
        return agentId;
    }

    public static EmailCategory fromAgentId(String agentId) {
        for (EmailCategory category : values()) {
            if (category.agentId.equalsIgnoreCase(agentId)) {
                return category;
            }
        }
        return GENERIC;
    }
}

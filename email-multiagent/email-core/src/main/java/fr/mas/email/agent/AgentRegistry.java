package fr.mas.email.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Registers specialized agents and resolves them by category. */
public final class AgentRegistry {

    private final Map<String, Agent> agents = new LinkedHashMap<>();

    public void register(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        agents.put(agent.name(), agent);
    }

    public Agent find(String category) {
        for (Agent agent : agents.values()) {
            if (agent.supports(category)) {
                return agent;
            }
        }
        return null;
    }

    public Map<String, Agent> all() {
        return Map.copyOf(agents);
    }
}

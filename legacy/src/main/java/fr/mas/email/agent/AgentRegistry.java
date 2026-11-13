package fr.mas.email.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import fr.mas.email.model.EmailCategory;

/**
 * Registre des agents disponibles (pattern <em>Repository</em>).
 *
 * <p>Permet d'ajouter ou de retirer des agents dynamiquement sans modifier le
 * coordinateur, qui ne connait que le contrat {@link Agent}.</p>
 */
public final class AgentRegistry {

    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private Agent fallback;

    public void register(Agent agent) {
        agents.put(agent.id(), agent);
        if (agent.category() == EmailCategory.GENERIC) {
            this.fallback = agent;
        }
    }

    public Optional<Agent> find(String id) {
        return Optional.ofNullable(agents.get(id));
    }

    public Agent fallback() {
        if (fallback == null) {
            throw new IllegalStateException("Aucun agent de repli (generic) enregistre");
        }
        return fallback;
    }

    public Collection<Agent> all() {
        return agents.values();
    }

    public int size() {
        return agents.size();
    }
}

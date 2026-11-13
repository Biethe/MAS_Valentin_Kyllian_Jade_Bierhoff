package fr.mas.email.routing;

import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

/**
 * Strategie de selection de l'agent cible (pattern <em>Strategy</em>).
 *
 * <p>Permet de remplacer le routage (LLM, mots-cles, hybride, plusieurs
 * niveaux...) sans modifier le coordinateur.</p>
 */
@FunctionalInterface
public interface RouterStrategy {

    RoutingDecision route(EmailMessage email, AgentRegistry registry,
                          ConversationMemory memory, int maxHistoryMessages);
}

package fr.mas.email.agent;

import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;

/**
 * Contrat commun a tous les agents du systeme.
 *
 * <p>Un agent possede une identite, une categorie, un prompt systeme et un
 * contexte propre. Il produit une proposition de reponse a partir d'un email
 * et de la memoire du fil de discussion.</p>
 */
public interface Agent {

    String id();

    String description();

    EmailCategory category();

    AgentResponse handle(EmailMessage email, ConversationMemory memory);

    AgentResponse handle(EmailMessage email, ConversationMemory memory, String userComment);
}

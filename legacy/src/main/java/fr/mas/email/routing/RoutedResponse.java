package fr.mas.email.routing;

import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.RoutingDecision;

/**
 * Resultat du traitement d'un email : decision de routage + proposition.
 */
public record RoutedResponse(RoutingDecision decision, AgentResponse response) {
}

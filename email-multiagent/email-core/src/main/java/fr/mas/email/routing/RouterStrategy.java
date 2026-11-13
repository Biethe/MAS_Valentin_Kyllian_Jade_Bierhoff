package fr.mas.email.routing;

import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

/** Chooses the agent that should handle an email. */
public interface RouterStrategy {

    RoutingDecision route(EmailMessage email);
}

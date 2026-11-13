package fr.mas.email.agent;

import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailMessage;

import java.util.List;

/** A specialized agent able to draft a reply to an email. */
public interface Agent {

    String name();

    boolean supports(String category);

    AgentResponse handle(EmailMessage email, List<String> conversationHistory);
}

package fr.mas.email;

import fr.mas.email.app.AppContext;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.system.EmailAssistantSystem;

/**
 * Console entry point. It reuses the same composition root as the web app, so
 * the console and the server always exercise the same wiring.
 */
public final class Main {

    public static void main(String[] args) {
        AppContext context = AppContext.create();

        EmailMessage email = new EmailMessage(
                "student@university.fr",
                "lecturer@university.fr",
                "Question about the multi-agent course",
                "Hello, I would like to know the deadline for the assignment. Thanks.",
                "thread-demo-1");

        EmailAssistantSystem.RoutedResponse routed = context.system().handle(email);
        System.out.println("LLM client: " + context.config().get("llm.client", "mock"));
        System.out.println("Routed to: " + routed.getResponse().getAgentName());
        System.out.println("Decision: " + routed.getDecision());
        System.out.println();
        System.out.println("Proposed reply:");
        System.out.println(routed.getResponse().getContent());
    }
}

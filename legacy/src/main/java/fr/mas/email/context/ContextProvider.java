package fr.mas.email.context;

import fr.mas.email.model.EmailMessage;

/**
 * Source de contexte propre a un agent.
 *
 * <p>Chaque agent specialise peut disposer d'un contexte different
 * (documents de projet, procedures, anciens echanges...).</p>
 */
@FunctionalInterface
public interface ContextProvider {

    String provideContext(EmailMessage email);

    static ContextProvider empty() {
        return email -> "";
    }
}

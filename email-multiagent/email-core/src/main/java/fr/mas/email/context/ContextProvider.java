package fr.mas.email.context;

import fr.mas.email.model.EmailCategory;

/** Supplies the document context associated with an agent. */
public interface ContextProvider {

    String contextFor(EmailCategory category);

    /**
     * Retrieves the context most relevant to the given query. The default
     * implementation ignores the query and returns the whole category context.
     */
    default String contextFor(EmailCategory category, String query) {
        return contextFor(category);
    }
}

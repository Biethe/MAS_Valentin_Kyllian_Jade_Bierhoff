package fr.mas.email.context;

/** What the assistant knows about a recipient. */
public record RecipientKnowledge(
        String email,
        String name,
        String role,
        String organization,
        String notes) {
}

package fr.mas.email.llm;

import java.util.List;
import java.util.Locale;

/**
 * Implementation hors-ligne deterministe du {@link LLMClient}.
 *
 * <p>Utile pour les tests et pour faire tourner le framework sans moteur
 * d'inference installe.</p>
 */
public final class MockLLMClient implements LLMClient {

    @Override
    public String complete(String systemPrompt, List<ChatMessage> messages) {
        String lastUser = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        String lower = lastUser.toLowerCase(Locale.ROOT);
        String system = systemPrompt == null ? "" : systemPrompt.toLowerCase(Locale.ROOT);

        if (system.contains("routeur") || lower.contains("rends uniquement le json")) {
            return route(lower);
        }
        return "[Reponse simulee] Merci pour votre message. "
                + "Voici une proposition de reponse generee hors-ligne "
                + "(mode mock, aucun LLM reel n'est appele).";
    }

    private String route(String lower) {
        int marker = lower.lastIndexOf("email a classer");
        String emailSection = marker >= 0 ? lower.substring(marker) : lower;
        String agent;
        double confidence;
        if (emailSection.contains("role connu : student")) {
            agent = "student";
            confidence = 0.85;
        } else if (emailSection.contains("role connu : administration")) {
            agent = "administration";
            confidence = 0.85;
        } else if (emailSection.contains("role connu : teacher")) {
            agent = "teacher";
            confidence = 0.85;
        } else if (containsAny(emailSection, "etudiant", "cours", "note", "stage", "soutenance")) {
            agent = "student";
            confidence = 0.7;
        } else if (containsAny(emailSection, "administration", "mission", "contrat", "facture")) {
            agent = "administration";
            confidence = 0.7;
        } else if (containsAny(emailSection, "collegue", "enseignant", "recherche", "article", "projet")) {
            agent = "teacher";
            confidence = 0.65;
        } else {
            agent = "generic";
            confidence = 0.3;
        }
        return "{\"agent\": \"" + agent + "\", \"confidence\": " + confidence
                + ", \"rationale\": \"routage heuristique (mode mock)\"}";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String modelName() {
        return "mock";
    }
}

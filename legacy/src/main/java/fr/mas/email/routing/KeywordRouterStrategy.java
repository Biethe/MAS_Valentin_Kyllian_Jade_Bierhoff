package fr.mas.email.routing;

import fr.mas.email.agent.Agent;
import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;
import fr.mas.email.model.SenderRole;

import java.util.Locale;

/**
 * Routage heuristique par mots-cles et role de l'expediteur.
 *
 * <p>Sert de repli rapide et deterministe lorsque le routage par LLM echoue
 * ou renvoie une decison inexploitable.</p>
 */
public final class KeywordRouterStrategy implements RouterStrategy {

    @Override
    public RoutingDecision route(EmailMessage email, AgentRegistry registry,
                                 ConversationMemory memory, int maxHistoryMessages) {
        String text = (email.subject() + " " + email.body()).toLowerCase(Locale.ROOT);

        String agentId = switch (email.senderRole()) {
            case STUDENT -> "student";
            case TEACHER -> "teacher";
            case ADMINISTRATION -> "administration";
            case EXTERNAL, UNKNOWN -> matches(text);
        };

        String rationale = "Routage heuristique (role=" + email.senderRole() + ")";
        double confidence = "generic".equals(agentId) ? 0.3 : 0.6;
        return new RoutingDecision(agentId, EmailCategory.fromAgentId(agentId), confidence, rationale);
    }

    private static String matches(String text) {
        if (containsAny(text, "etudiant", "cours", "notes", "stage", "soutenance", "td", "tp")) {
            return "student";
        }
        if (containsAny(text, "mission", "contrat", "facture", "remboursement", "rh", "scolarite")) {
            return "administration";
        }
        if (containsAny(text, "jury", "collegue", "recherche", "article", "publication", "reunion")) {
            return "teacher";
        }
        return "generic";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}

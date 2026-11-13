package fr.mas.email.routing;

import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic keyword router used as a fallback and as a baseline for
 * experiments. It never depends on the LLM.
 */
public final class KeywordRouterStrategy implements RouterStrategy {

    private final double fallbackThreshold;

    public KeywordRouterStrategy(double fallbackThreshold) {
        this.fallbackThreshold = fallbackThreshold;
    }

    @Override
    public RoutingDecision route(EmailMessage email) {
        String text = (email.getSubject() + " " + email.getBody()).toLowerCase(Locale.ROOT);

        EmailCategory best = EmailCategory.GENERIC;
        double bestScore = 0.0;
        String bestReason = "no keyword matched";

        if (contains(text, "unsubscribe", "promotion", "phishing", "winner", "prize", "gagnez", "publicité", "publicite", "démarchage", "demarchage")) {
            best = EmailCategory.SPAM;
            bestScore = 0.9;
            bestReason = "spam/promotion keywords";
        }
        if (contains(text, "newsletter", "notification", "no-reply", "noreply", "do not reply", "ne pas répondre", "ne pas repondre", "automatique")) {
            if (bestScore < 0.95) {
                best = EmailCategory.NO_REPLY;
                bestScore = 0.95;
                bestReason = "no-reply/notification keywords";
            }
        }
        if (contains(text, "student", "cours", "course", "devoir", "assignment", "examen", "exam", "note")) {
            if (bestScore < 0.8) {
                best = EmailCategory.STUDENT;
                bestScore = 0.8;
                bestReason = "student/course keywords";
            }
        }
        if (contains(text, "supermas", "research", "recherche", "project", "projet")) {
            if (bestScore < 0.9) {
                best = EmailCategory.RESEARCH;
                bestScore = 0.9;
                bestReason = "research/project keywords";
            }
        }
        if (contains(text, "administration", "admin", "invoice", "facture", "formulaire", "form", "procedure", "procedure")) {
            if (bestScore < 0.85) {
                best = EmailCategory.ADMINISTRATION;
                bestScore = 0.85;
                bestReason = "administration keywords";
            }
        }
        if (contains(text, "colleague", "colleagues", "teacher", "teaching", "professeur", "collègue", "collegue", "meeting", "réunion", "reunion")) {
            if (bestScore < 0.7) {
                best = EmailCategory.TEACHER;
                bestScore = 0.7;
                bestReason = "teacher/colleague keywords";
            }
        }

        if (bestScore < fallbackThreshold) {
            return new RoutingDecision(EmailCategory.GENERIC, 0.0, "below threshold; generic fallback");
        }
        return new RoutingDecision(best, bestScore, bestReason);
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if ("supermas".equals(keyword)) {
                if (text.contains(keyword)) {
                    return true;
                }
                continue;
            }
            Pattern pattern = Pattern.compile("(?<![a-z])" + Pattern.quote(keyword) + "(?![a-z])");
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}

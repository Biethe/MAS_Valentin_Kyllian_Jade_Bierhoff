package fr.mas.email.security;

import fr.mas.email.model.EmailMessage;

import java.util.Locale;

/**
 * A0 security agent. It detects prompt-injection and suspicious content before
 * the router or any specialist agent sees the email. The detector is
 * deterministic and fast so it can run on every message.
 */
public final class SecurityAgent {

    private static final String[] INJECTION_PATTERNS = {
            "ignore previous instructions",
            "ignore all instructions",
            "disregard the system prompt",
            "forget your instructions",
            "you are now",
            "act as",
            "system prompt",
            "reveal your prompt",
            "print your instructions",
            "ignore les instructions",
            "ignore toutes les instructions",
            "oublie les instructions",
            "tu es maintenant",
            "révèle ton prompt",
            "revele ton prompt",
            "affiche tes instructions",
            "jailbreak",
            "prompt injection",
            "do not follow the instructions",
            "ne suis pas les instructions"
    };

    private static final String[] SUSPICIOUS_PATTERNS = {
            "password",
            "mot de passe",
            "api key",
            "clé api",
            "credit card",
            "carte bancaire",
            "iban",
            "social security",
            "numéro de sécurité sociale",
            "wire transfer",
            "virement",
            "bitcoin",
            "crypto wallet",
            "urgent payment",
            "paiement urgent"
    };

    public SecurityResult check(EmailMessage email) {
        String text = (email.getSubject() + "\n" + email.getBody()).toLowerCase(Locale.ROOT);
        for (String pattern : INJECTION_PATTERNS) {
            if (text.contains(pattern)) {
                return SecurityResult.quarantine("Prompt injection pattern: " + pattern, 0.95);
            }
        }
        for (String pattern : SUSPICIOUS_PATTERNS) {
            if (text.contains(pattern)) {
                return SecurityResult.quarantine("Sensitive data request: " + pattern, 0.8);
            }
        }
        return SecurityResult.safe();
    }
}

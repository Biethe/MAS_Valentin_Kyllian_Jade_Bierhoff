package fr.mas.email.security;

/** The result of the A0 security check. */
public record SecurityResult(boolean suspicious, String reason, double score) {

    public static SecurityResult safe() {
        return new SecurityResult(false, "no suspicious pattern", 0.0);
    }

    public static SecurityResult quarantine(String reason, double score) {
        return new SecurityResult(true, reason, score);
    }
}

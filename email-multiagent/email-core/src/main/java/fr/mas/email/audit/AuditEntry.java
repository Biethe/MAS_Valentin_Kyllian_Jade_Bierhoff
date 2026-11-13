package fr.mas.email.audit;

import java.time.Instant;

/** One audit event. */
public record AuditEntry(
        String timestamp,
        String threadId,
        String sender,
        String subject,
        String category,
        String agent,
        double confidence,
        String reason,
        String action,
        boolean quarantined,
        long durationMs,
        String detail) {

    public static AuditEntry of(String threadId,
                                String sender,
                                String subject,
                                String category,
                                String agent,
                                double confidence,
                                String reason,
                                String action,
                                boolean quarantined,
                                long durationMs,
                                String detail) {
        return new AuditEntry(
                Instant.now().toString(),
                threadId,
                sender,
                subject,
                category,
                agent,
                confidence,
                reason,
                action,
                quarantined,
                durationMs,
                detail);
    }
}

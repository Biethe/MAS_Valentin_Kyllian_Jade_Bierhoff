package fr.mas.email.metrics;

import fr.mas.email.audit.AuditEntry;
import fr.mas.email.audit.AuditLog;
import fr.mas.email.feedback.FeedbackEntry;
import fr.mas.email.feedback.FeedbackStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Computes the dashboard metrics from the audit log and feedback. */
public final class MetricsService {

    private final AuditLog auditLog;
    private final FeedbackStore feedback;

    public MetricsService(AuditLog auditLog, FeedbackStore feedback) {
        this.auditLog = auditLog;
        this.feedback = feedback;
    }

    public MetricsSummary summary() {
        var audits = auditLog.all();
        var feedbacks = feedback.all();

        int routed = 0;
        int quarantined = 0;
        int sent = 0;
        long totalLatency = 0;
        int latencyCount = 0;
        Map<String, Integer> categories = new LinkedHashMap<>();

        for (AuditEntry entry : audits) {
            if ("route".equals(entry.action())) {
                routed++;
                if (entry.quarantined()) {
                    quarantined++;
                }
                if (entry.category() != null) {
                    categories.merge(entry.category(), 1, Integer::sum);
                }
                if (entry.durationMs() > 0) {
                    totalLatency += entry.durationMs();
                    latencyCount++;
                }
            } else if ("send".equals(entry.action())) {
                sent++;
            }
        }

        int accepted = 0;
        int rejected = 0;
        for (FeedbackEntry entry : feedbacks) {
            if ("accept".equalsIgnoreCase(entry.action())) {
                accepted++;
            } else if ("reject".equalsIgnoreCase(entry.action())) {
                rejected++;
            }
        }

        double acceptanceRate = accepted + rejected == 0 ? 0.0 : (double) accepted / (accepted + rejected);
        double averageLatency = latencyCount == 0 ? 0.0 : (double) totalLatency / latencyCount;

        return new MetricsSummary(
                routed,
                quarantined,
                sent,
                accepted,
                rejected,
                acceptanceRate,
                averageLatency,
                categories);
    }

    public record MetricsSummary(
            int routed,
            int quarantined,
            int sent,
            int accepted,
            int rejected,
            double acceptanceRate,
            double averageLatencyMs,
            Map<String, Integer> categories) {
    }
}

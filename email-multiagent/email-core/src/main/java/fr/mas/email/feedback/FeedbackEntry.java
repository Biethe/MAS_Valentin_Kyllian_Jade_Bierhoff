package fr.mas.email.feedback;

import java.time.Instant;

/** One user decision on a draft. */
public record FeedbackEntry(
        String timestamp,
        String threadId,
        String sender,
        String category,
        String action,
        double confidence,
        String draft) {

    public static FeedbackEntry of(String threadId,
                                   String sender,
                                   String category,
                                   String action,
                                   double confidence,
                                   String draft) {
        return new FeedbackEntry(
                Instant.now().toString(),
                threadId,
                sender,
                category,
                action,
                confidence,
                draft);
    }
}

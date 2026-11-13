package fr.mas.email.evaluation;

import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;

import java.util.Objects;

/** A labeled email used to evaluate a routing strategy. */
public record EvaluationCase(String sender,
                             String recipient,
                             String subject,
                             String body,
                             EmailCategory expected) {

    public EvaluationCase {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(expected, "expected");
    }

    public EmailMessage toEmailMessage() {
        return new EmailMessage(sender, recipient, subject, body, "eval-" + Math.abs(hashCode()));
    }
}

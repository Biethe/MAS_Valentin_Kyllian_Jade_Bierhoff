package fr.mas.email.evaluation;

import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.RoutingDecision;

/** One evaluated case. */
public record EvaluationCaseResult(EvaluationCase testCase,
                                   RoutingDecision decision,
                                   boolean correct) {

    public EmailCategory expected() {
        return testCase.expected();
    }

    public EmailCategory predicted() {
        return decision.getCategory();
    }
}

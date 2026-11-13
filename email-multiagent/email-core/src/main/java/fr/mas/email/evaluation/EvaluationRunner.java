package fr.mas.email.evaluation;

import fr.mas.email.model.RoutingDecision;
import fr.mas.email.routing.RouterStrategy;

import java.util.ArrayList;
import java.util.List;

/** Runs a router strategy over a labeled dataset. */
public final class EvaluationRunner {

    private EvaluationRunner() {
    }

    public static EvaluationResult run(String strategyName, RouterStrategy strategy, List<EvaluationCase> cases) {
        List<EvaluationCaseResult> results = new ArrayList<>();
        for (EvaluationCase testCase : cases) {
            RoutingDecision decision = strategy.route(testCase.toEmailMessage());
            results.add(new EvaluationCaseResult(testCase, decision, decision.getCategory() == testCase.expected()));
        }
        return new EvaluationResult(strategyName, results);
    }
}

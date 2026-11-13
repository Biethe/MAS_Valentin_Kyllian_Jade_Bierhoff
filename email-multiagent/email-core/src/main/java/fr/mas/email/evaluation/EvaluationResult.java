package fr.mas.email.evaluation;

import fr.mas.email.model.EmailCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregated results of a routing strategy over a dataset. */
public final class EvaluationResult {

    private final String strategyName;
    private final List<EvaluationCaseResult> cases;

    public EvaluationResult(String strategyName, List<EvaluationCaseResult> cases) {
        this.strategyName = strategyName;
        this.cases = List.copyOf(cases);
    }

    public String getStrategyName() {
        return strategyName;
    }

    public List<EvaluationCaseResult> getCases() {
        return cases;
    }

    public int total() {
        return cases.size();
    }

    public int correct() {
        return (int) cases.stream().filter(EvaluationCaseResult::correct).count();
    }

    public double accuracy() {
        return total() == 0 ? 0.0 : (double) correct() / total();
    }

    public Map<EmailCategory, CategoryStats> perCategory() {
        Map<EmailCategory, CategoryStats> stats = new LinkedHashMap<>();
        for (EmailCategory category : EmailCategory.values()) {
            stats.put(category, new CategoryStats(0, 0));
        }
        for (EvaluationCaseResult result : cases) {
            CategoryStats current = stats.get(result.expected());
            stats.put(result.expected(), new CategoryStats(current.correct() + (result.correct() ? 1 : 0),
                    current.total() + 1));
        }
        return stats;
    }

    public record CategoryStats(int correct, int total) {
        public double recall() {
            return total == 0 ? 0.0 : (double) correct / total;
        }
    }
}

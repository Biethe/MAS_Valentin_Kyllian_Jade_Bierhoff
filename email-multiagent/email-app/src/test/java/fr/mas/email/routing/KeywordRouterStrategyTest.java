package fr.mas.email.routing;

import fr.mas.email.evaluation.DatasetLoader;
import fr.mas.email.evaluation.EvaluationCase;
import fr.mas.email.evaluation.EvaluationResult;
import fr.mas.email.evaluation.EvaluationRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordRouterStrategyTest {

    @Test
    void keywordRouterClassifiesTheDataset() {
        List<EvaluationCase> cases = DatasetLoader.loadFromClasspath();
        EvaluationResult result = EvaluationRunner.run(
                "Keyword", new KeywordRouterStrategy(0.5), cases);
        assertEquals(cases.size(), result.total());
        assertEquals(cases.size(), result.correct());
    }
}

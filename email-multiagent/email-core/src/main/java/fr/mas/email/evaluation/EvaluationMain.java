package fr.mas.email.evaluation;

import fr.mas.email.config.AppConfig;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMClientFactory;
import fr.mas.email.routing.HybridRouterStrategy;
import fr.mas.email.routing.KeywordRouterStrategy;
import fr.mas.email.routing.LLMRouterStrategy;
import fr.mas.email.routing.RouterStrategy;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Compares the keyword, LLM and hybrid routers on a labeled dataset.
 * Usage: {@code mvn compile exec:java -Dexec.mainClass=fr.mas.email.evaluation.EvaluationMain}
 * or pass {@code --dataset path/to/emails.jsonl}.
 */
public final class EvaluationMain {

    public static void main(String[] args) {
        Path dataset = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--dataset".equals(args[i])) {
                dataset = Path.of(args[i + 1]);
            }
        }

        List<EvaluationCase> cases = dataset == null
                ? DatasetLoader.loadFromClasspath()
                : DatasetLoader.loadFromFile(dataset);

        AppConfig config = AppConfig.load();
        LLMClient llmClient = LLMClientFactory.create(config);
        double keywordThreshold = config.getDouble("routing.threshold", 0.5);
        double llmThreshold = config.getDouble("routing.llm.confidence", 0.6);
        double hybridThreshold = config.getDouble("routing.hybrid.confidence", 0.6);

        RouterStrategy keyword = new KeywordRouterStrategy(keywordThreshold);
        RouterStrategy llm = new LLMRouterStrategy(llmClient, keyword, llmThreshold);
        RouterStrategy hybrid = new HybridRouterStrategy(keyword, llm, hybridThreshold);

        EvaluationResult keywordResult = EvaluationRunner.run("Keyword", keyword, cases);
        EvaluationResult llmResult = EvaluationRunner.run("LLM", llm, cases);
        EvaluationResult hybridResult = EvaluationRunner.run("Hybrid", hybrid, cases);

        print(keywordResult);
        print(llmResult);
        print(hybridResult);
        printComparison(keywordResult, llmResult, hybridResult);
    }

    private static void print(EvaluationResult result) {
        System.out.println();
        System.out.println("=== " + result.getStrategyName() + " router ===");
        System.out.printf(Locale.ROOT, "Accuracy: %d/%d (%.1f%%)%n",
                result.correct(), result.total(), result.accuracy() * 100);
        for (EvaluationCaseResult testCase : result.getCases()) {
            System.out.printf(Locale.ROOT, "  %-16s expected=%-14s predicted=%-14s %s%n",
                    testCase.testCase().subject().isBlank() ? "(no subject)" : testCase.testCase().subject(),
                    testCase.expected(),
                    testCase.predicted(),
                    testCase.correct() ? "OK" : "MISS");
        }
        System.out.println("  Per category:");
        for (var entry : result.perCategory().entrySet()) {
            if (entry.getValue().total() > 0) {
                System.out.printf(Locale.ROOT, "    %-16s %d/%d (%.1f%%)%n",
                        entry.getKey(), entry.getValue().correct(), entry.getValue().total(),
                        entry.getValue().recall() * 100);
            }
        }
    }

    private static void printComparison(EvaluationResult keyword,
                                        EvaluationResult llm,
                                        EvaluationResult hybrid) {
        System.out.println();
        System.out.println("=== Comparison ===");
        System.out.printf(Locale.ROOT, "Keyword: %d/%d (%.1f%%)%n",
                keyword.correct(), keyword.total(), keyword.accuracy() * 100);
        System.out.printf(Locale.ROOT, "LLM:     %d/%d (%.1f%%)%n",
                llm.correct(), llm.total(), llm.accuracy() * 100);
        System.out.printf(Locale.ROOT, "Hybrid:  %d/%d (%.1f%%)%n",
                hybrid.correct(), hybrid.total(), hybrid.accuracy() * 100);
        System.out.println();
        System.out.println("Disagreements:");
        boolean any = false;
        for (int i = 0; i < keyword.total(); i++) {
            EvaluationCaseResult k = keyword.getCases().get(i);
            EvaluationCaseResult l = llm.getCases().get(i);
            EvaluationCaseResult h = hybrid.getCases().get(i);
            if (k.predicted() != l.predicted() || k.predicted() != h.predicted()) {
                any = true;
                System.out.printf(Locale.ROOT, "  %-40s expected=%-14s keyword=%-14s llm=%-14s hybrid=%-14s%n",
                        k.testCase().subject(), k.expected(), k.predicted(), l.predicted(), h.predicted());
            }
        }
        if (!any) {
            System.out.println("  (none)");
        }
    }
}

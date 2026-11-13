package fr.mas.email.routing;

import fr.mas.email.config.AppConfig;
import fr.mas.email.llm.LLMClient;

/**
 * Builds the routing strategy selected by configuration. {@code hybrid} uses
 * the LLM with a keyword fallback; {@code keyword} uses the deterministic
 * strategy only.
 */
public final class RouterFactory {

    private RouterFactory() {
    }

    public static RouterStrategy create(AppConfig config, LLMClient llmClient) {
        String strategy = config.get("routing.strategy", "hybrid").trim().toLowerCase();
        double keywordThreshold = config.getDouble("routing.threshold", 0.5);
        RouterStrategy keyword = new KeywordRouterStrategy(keywordThreshold);
        if ("keyword".equals(strategy)) {
            return keyword;
        }
        RouterStrategy llm = new LLMRouterStrategy(
                llmClient, keyword, config.getDouble("routing.llm.confidence", 0.6));
        if ("llm".equals(strategy)) {
            return llm;
        }
        return new HybridRouterStrategy(
                keyword, llm, config.getDouble("routing.hybrid.confidence", 0.6));
    }
}

package fr.mas.email.routing;

import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LLMRouterStrategyTest {

    private static EmailMessage email() {
        return new EmailMessage("a@b.fr", "c@d.fr", "Question", "Hello", "t1");
    }

    @Test
    void usesTheLlmCategoryWhenConfident() {
        LLMClient llm = messages -> "STUDENT|0.9|course question";
        RouterStrategy fallback = new KeywordRouterStrategy(0.5);
        RoutingDecision decision = new LLMRouterStrategy(llm, fallback, 0.6).route(email());
        assertEquals(EmailCategory.STUDENT, decision.getCategory());
        assertEquals(0.9, decision.getConfidence());
    }

    @Test
    void fallsBackWhenTheLlmIsNotConfident() {
        LLMClient llm = messages -> "STUDENT|0.2|not sure";
        RouterStrategy fallback = new KeywordRouterStrategy(0.5);
        RoutingDecision decision = new LLMRouterStrategy(llm, fallback, 0.6).route(email());
        assertEquals(EmailCategory.GENERIC, decision.getCategory());
    }

    @Test
    void fallsBackWhenTheLlmReturnsAnInvalidCategory() {
        LLMClient llm = messages -> "I cannot classify this.";
        RouterStrategy fallback = new KeywordRouterStrategy(0.5);
        RoutingDecision decision = new LLMRouterStrategy(llm, fallback, 0.6).route(email());
        assertEquals(EmailCategory.GENERIC, decision.getCategory());
    }

    @Test
    void fallsBackWhenTheLlmThrows() {
        LLMClient llm = messages -> {
            throw new fr.mas.email.llm.LLMException("down");
        };
        RouterStrategy fallback = new KeywordRouterStrategy(0.5);
        RoutingDecision decision = new LLMRouterStrategy(llm, fallback, 0.6).route(email());
        assertEquals(EmailCategory.GENERIC, decision.getCategory());
    }
}

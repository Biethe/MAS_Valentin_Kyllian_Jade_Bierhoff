package com.assistant.mail.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import java.util.List;
import org.junit.jupiter.api.Test;

class TolerantRepliesTest {
    @Test void functionStyleToolCallBecomesBlocks() {
        String out = TolerantReplies.normalize(PlannerSystemPrompt.TOOLS, "search_mail(\"Ada\")");
        assertEquals("name:\nsearch_mail\n\nquery:\nAda", out);
    }

    @Test void echoedTemplateWithAQueryBecomesASearch() {
        String out = TolerantReplies.normalize(PlannerSystemPrompt.TOOLS,
                "search_mail|read_mail|read_thread\nquery: Ada\nid: \nthreadId: \ndone: yes");
        assertEquals("name:\nsearch_mail\n\nquery:\nAda", out);
    }

    @Test void properBlocksAndDoneAreLeftAlone() {
        assertEquals("done: yes", TolerantReplies.normalize(PlannerSystemPrompt.TOOLS, "done: yes"));
        String blocks = "name:\nread_mail\n\nid:\n3";
        assertEquals(blocks, TolerantReplies.normalize(PlannerSystemPrompt.TOOLS, blocks));
    }

    @Test void codeFencesAreDropped() {
        assertEquals("type: REPLY", TolerantReplies.unfence("```\ntype: REPLY\n```"));
    }

    @Test void inlineDraftFieldsMoveToTheNextLine() {
        String out = TolerantReplies.draftBlocks(DrafterSystemPrompt.TEXT,
                "recipients:\nada@u.test\n\nsubject: Hello\n\nbody:  \nBonjour");
        assertTrue(out.contains("subject:\nHello"), out);
        assertTrue(out.contains("body:\nBonjour"), out);
    }

    @Test void anEchoedFactTemplateFallsBackToTheWholeMail() {
        String user = "instruction:\nRésume\n\nmail:\nRéunion vendredi à 10h.";
        String out = TolerantReplies.normalizeFor(PlannerSystemPrompt.READ, user, "<one fact>\nkind: INFORMATIVE\n</one fact>");
        assertEquals("body:\nRéunion vendredi à 10h.\n\nkind:\nINFORMATIVE", out);
        assertTrue(TolerantReplies.hasFact("body:\nUn fait\n\nkind:\nINFORMATIVE"));
        assertFalse(TolerantReplies.hasFact("body: <one fact>"));
    }

    @Test void thinkingTagsAreStripped() {
        assertEquals("answer", OllamaChat.stripThinking("<think>hmm\nmore</think>\nanswer"));
    }

    @Test void pickPrefersTheRequestedThenAGoodInstalledModel() {
        List<String> installed = List.of("llama3.2:1b", "qwen3:0.6b", "huihui_ai/qwen3-abliterated:4b");
        assertEquals("llama3.1:8b", OllamaChat.pick("llama3.1:8b", List.of("llama3.1:8b")));
        assertEquals("huihui_ai/qwen3-abliterated:4b", OllamaChat.pick("llama3.1:8b", installed));
        assertNull(OllamaChat.pick("llama3.1:8b", List.of("llama3.2:1b", "qwen3:0.6b")));
    }
}

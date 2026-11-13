package com.assistant.mail.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.llm.ScriptedModels;
import com.assistant.mail.mailbox.InMemoryMailbox;
import com.assistant.mail.model.mail.Email;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadInFullTest {
    /** A mailbox whose search returns snippets, like the MCP one: the full body needs read_mail. */
    private static final class SnippetMailbox extends InMemoryMailbox {
        final List<String> reads = new ArrayList<>();

        SnippetMailbox() { super(InMemoryMailbox.demo().all()); }

        @Override public List<Email> searchMail(String query) {
            List<Email> snippets = new ArrayList<>();
            for (Email full : super.searchMail(query)) {
                Email s = new Email();
                s.setId(full.getId());
                s.setThreadId(full.getThreadId());
                s.setSender(full.getSender());
                s.setSubject(full.getSubject());
                s.setBody("snippet only");
                snippets.add(s);
            }
            return snippets;
        }

        @Override public boolean unread(Long id) { return !reads.contains(String.valueOf(id)); }

        @Override public Email readMail(String id) {
            reads.add(id);
            return super.readMail(id);
        }
    }

    @Test void aSnippetHitIsOpenedBeforeItsFactsAreRead() {
        SnippetMailbox box = new SnippetMailbox();
        ScriptedModels base = new ScriptedModels();
        List<String> readPrompts = new ArrayList<>();
        com.assistant.mail.service.agent.PlannerAgentClient spy = (system, user) -> {
            if (com.assistant.mail.prompt.PlannerSystemPrompt.READ.equals(system)) readPrompts.add(user);
            return base.complete(system, user);
        };
        Turn.Outcome o = new Assistant(box, spy, base, base, "snippet", () -> { }).ask("Résume le mail d'Ada");
        assertEquals(List.of("1"), box.reads);
        assertEquals(1, o.summaries.size());
        assertTrue(readPrompts.get(0).contains("Réunion vendredi à 10h."), readPrompts.toString());
    }

    @Test void askingForTheThreadReadsEveryMailOfIt() {
        Email first = InMemoryMailbox.demo().all().get(0);
        Email second = new Email();
        second.setId(2L);
        second.setThreadId(first.getThreadId());
        second.setSender(first.getSender());
        second.setSubject("Re: RDV");
        second.setBody("Finalement salle B204 vendredi.");
        InMemoryMailbox box = new InMemoryMailbox(List.of(first, second));
        ScriptedModels base = new ScriptedModels();
        com.assistant.mail.service.agent.PlannerAgentClient fil = (system, user) ->
                com.assistant.mail.prompt.PlannerSystemPrompt.SPLIT.equals(system) ? "body:\nRésume le fil avec Ada" : base.complete(system, user);
        Turn.Outcome o = new Assistant(box, fil, base, base, "thread", () -> { }).ask("Résume le fil avec Ada");
        assertEquals(1, o.briefs.size());
        assertEquals(2, o.briefs.get(0).getEmails().size());
        assertTrue(Planner.asksForThread("Résume-moi le fil sur la journée d'étude"));
        assertTrue(!Planner.asksForThread("Résume le mail d'Ada"));
    }

    @Test void theWholeShortMailTravelsAsAFact() {
        Turn.Outcome o = new Assistant(InMemoryMailbox.demo(), new ScriptedModels(), new ScriptedModels(), new ScriptedModels(),
                "facts", () -> { }).ask("Réponds à Ada");
        assertTrue(o.briefs.get(0).getFacts().stream().anyMatch(f -> "Réunion vendredi à 10h.".equals(f.getBody())));
    }

    @Test void aSummaryIsNotPollutedByProfilePassages() {
        List<String> seen = new ArrayList<>();
        ScriptedModels base = new ScriptedModels();
        com.assistant.mail.service.agent.SummarizerAgentClient spy = (system, user) -> {
            if (com.assistant.mail.prompt.SummarizerSystemPrompt.TEXT.equals(system)) seen.add(user);
            return base.complete(system, user);
        };
        Assistant a = new Assistant(InMemoryMailbox.demo(), base, base, spy, "pollution", () -> { })
                .withKnowledge((recipient, role, query) -> List.of("Contact ada@u.test: Ada", "[admin/p.txt] Expense claims need receipts."));
        assertEquals(1, a.ask("Résume le mail d'Ada").summaries.size());
        assertTrue(seen.get(0).contains("knowledge: Contact ada@u.test"));
        assertTrue(!seen.get(0).contains("Expense claims"), seen.get(0));
    }

    @Test void aLongSummaryIsAskedOnceToBeShorter() {
        com.assistant.mail.model.brief.Brief brief = new com.assistant.mail.model.brief.Brief();
        Email big = new Email();
        big.setBody("mot ".repeat(100));
        brief.setEmails(new ArrayList<>(List.of(big)));
        assertTrue(SummaryWorkflow.lengthCorrection(brief, "mot ".repeat(80)).contains("shorten to at most"));
        assertEquals(null, SummaryWorkflow.lengthCorrection(brief, "mot ".repeat(30)));
    }

    @Test void fitKeepsTheSentencesWithDatesAndPlaces() {
        String body = "Bonjour à tous et merci pour vos messages très aimables. La journée d'étude a lieu le vendredi 4 décembre en salle B204. "
                + "Nous espérons que tout le monde passe une excellente semaine. Le programme est attendu avant le 20 novembre.";
        String fitted = SummaryWorkflow.fit(body, 25);
        assertTrue(fitted.contains("4 décembre") && fitted.contains("20 novembre"), fitted);
        assertTrue(!fitted.contains("aimables") && !fitted.contains("excellente"), fitted);
        assertTrue(fitted.split("\\s+").length <= 25);
        assertEquals(5, SummaryWorkflow.fit("un deux trois quatre cinq six sept huit", 5).split(" ").length);
    }

    @Test void aDateNamedInTheRequestIsKeptInTheSummary() {
        com.assistant.mail.model.brief.Brief brief = new com.assistant.mail.model.brief.Brief();
        brief.setFocus("Résume-moi le fil sur la journée d'étude du 4 décembre.");
        Email mail = new Email();
        mail.setBody("Bonjour. Nous organisons la journée d'étude du vendredi 4 décembre. Merci pour les croissants.");
        brief.setEmails(new ArrayList<>(List.of(mail)));
        String kept = SummaryWorkflow.keepNamedDates(brief, "Le programme est attendu le 20 novembre.", 40);
        assertTrue(kept.startsWith("Nous organisons la journée d'étude du vendredi 4 décembre."), kept);
        assertTrue(kept.contains("20 novembre"));
        String already = "La journée d'étude a lieu le 4 décembre.";
        assertEquals(already, SummaryWorkflow.keepNamedDates(brief, already, 40));
        brief.setFocus("Résume le mail");
        assertEquals("x", SummaryWorkflow.keepNamedDates(brief, "x", 40));
    }
}

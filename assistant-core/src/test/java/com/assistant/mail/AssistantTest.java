package com.assistant.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.AssistantConfig;
import com.assistant.mail.app.OutcomeJson;
import com.assistant.mail.mailbox.InMemoryMailbox;
import com.assistant.mail.workflow.Turn;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantTest {
    private static Assistant scripted() {
        return Assistant.create(AssistantConfig.parse(new String[] {"--mock"}, Map.of()));
    }

    @Test void summarizesTheMail() {
        Turn.Outcome o = scripted().ask("Résume le mail d'Ada");
        assertEquals(1, o.summaries.size());
        assertTrue(o.summaries.get(0).getBody().contains("Réunion"));
        assertTrue(o.drafts.isEmpty());
    }

    @Test void repliesWithASavedDraftAndNeverSends() {
        Assistant a = scripted();
        Turn.Outcome o = a.ask("Réponds à Ada");
        assertEquals(1, o.drafts.size());
        assertEquals("ada@u.test", o.drafts.get(0).getRecipients().get(0));
        assertEquals(1, a.savedDrafts().size());
        assertEquals(o.drafts.get(0).getId(), a.savedDrafts().get(0).getId());
    }

    @Test void unclearRequestBecomesAQuestionAndSavesNothing() {
        Assistant a = scripted();
        Turn.Outcome o = a.ask("fais un truc");
        assertEquals(1, o.questions.size());
        assertTrue(a.savedDrafts().isEmpty());
    }

    @Test void smallTalkIsAnswered() {
        assertFalse(scripted().ask("bonjour").chats.isEmpty());
    }

    @Test void blankRequestDoesNothing() {
        Turn.Outcome o = scripted().ask("   ");
        assertTrue(o.drafts.isEmpty() && o.summaries.isEmpty() && o.questions.isEmpty());
    }

    @Test void outcomeJsonCarriesTheDraftAndTrace() {
        Turn.Outcome o = scripted().ask("Réponds à Ada");
        String json = OutcomeJson.toString(OutcomeJson.of(o, true));
        assertTrue(json.contains("\"drafts\"") && json.contains("ada@u.test") && json.contains("\"trace\""));
    }

    @Test void mailboxSearchRequiresEveryWord() {
        InMemoryMailbox box = InMemoryMailbox.demo();
        assertEquals(1, box.searchMail("ada réunion").size());
        assertTrue(box.searchMail("ada zebra").isEmpty());
        assertEquals("RDV", box.readMail("1").getSubject());
        assertEquals(1, box.readThread("t1").getEmails().size());
    }

    @Test void aModelThatIgnoresTheFormatGetsAnExplanationNotSilence() {
        com.assistant.mail.service.agent.PlannerAgentClient rambling = (system, user) -> "Le mail parle de technologie.";
        com.assistant.mail.llm.ScriptedModels unused = new com.assistant.mail.llm.ScriptedModels();
        Assistant a = new Assistant(InMemoryMailbox.demo(), rambling, unused, unused, "rambling", () -> { });
        Turn.Outcome o = a.ask("Résume le mail d'Ada");
        assertEquals(1, o.questions.size());
        assertTrue(o.questions.get(0).getText().contains("format"));
        assertTrue(o.drafts.isEmpty() && o.summaries.isEmpty());
    }

    @Test void aReplyWithoutSubjectKeepsTheMailSubject() {
        com.assistant.mail.llm.ScriptedModels base = new com.assistant.mail.llm.ScriptedModels();
        com.assistant.mail.service.agent.DrafterAgentClient noSubject = (system, user) -> {
            String r = base.complete(system, user);
            return r.replace("subject:\nRe: RDV", "subject:\n");
        };
        Assistant a = new Assistant(InMemoryMailbox.demo(), base, noSubject, base, "no-subject", () -> { });
        Turn.Outcome o = a.ask("Réponds à Ada");
        assertEquals(1, o.drafts.size());
        assertEquals("Re: RDV", o.drafts.get(0).getSubject());
    }

    @Test void doneBeforeAnySearchIsRetriedOnce() {
        com.assistant.mail.llm.ScriptedModels base = new com.assistant.mail.llm.ScriptedModels();
        int[] tools = {0};
        com.assistant.mail.service.agent.PlannerAgentClient lazy = (system, user) -> {
            if (com.assistant.mail.prompt.PlannerSystemPrompt.TOOLS.equals(system) && tools[0]++ == 0) return "done: yes";
            return base.complete(system, user);
        };
        Assistant a = new Assistant(InMemoryMailbox.demo(), lazy, base, base, "lazy", () -> { });
        assertEquals(1, a.ask("Résume le mail d'Ada").summaries.size());
    }

    @Test void searchWordsPreferNamesThenLongWords() {
        assertEquals(java.util.List.of("Dubois", "Prof", "rendez-vous", "confirmer"),
                com.assistant.mail.workflow.Planner.searchWords("Réponds à la Prof. Dubois pour confirmer le rendez-vous"));
    }

    @Test void aModelThatNeverSearchesStillFindsTheMailByItsWords() {
        com.assistant.mail.llm.ScriptedModels base = new com.assistant.mail.llm.ScriptedModels();
        com.assistant.mail.service.agent.PlannerAgentClient clumsy = (system, user) ->
                com.assistant.mail.prompt.PlannerSystemPrompt.TOOLS.equals(system) ? "read_mail" : base.complete(system, user);
        Assistant a = new Assistant(InMemoryMailbox.demo(), clumsy, base, base, "clumsy", () -> { });
        assertEquals(1, a.ask("Résume le mail d'Ada").summaries.size());
    }

    @Test void aReplyToTheUsersOwnAddressIsCorrectedToTheSender() {
        com.assistant.mail.model.brief.Brief brief = new com.assistant.mail.model.brief.Brief();
        brief.setTo(new java.util.ArrayList<>(java.util.List.of("moi@u.test")));
        com.assistant.mail.workflow.Planner.fixReplyRecipient(brief, InMemoryMailbox.demo().all().get(0));
        assertEquals(java.util.List.of("ada@u.test"), brief.getTo());
        brief.setTo(new java.util.ArrayList<>(java.util.List.of("tom@u.test")));
        com.assistant.mail.workflow.Planner.fixReplyRecipient(brief, InMemoryMailbox.demo().all().get(0),
                "Réponds à Ada et mets tom@u.test comme destinataire");
        assertEquals(java.util.List.of("tom@u.test"), brief.getTo()); // an address the user named is kept
        com.assistant.mail.workflow.Planner.fixReplyRecipient(brief, InMemoryMailbox.demo().all().get(0), "Réponds à Ada");
        assertEquals(java.util.List.of("ada@u.test"), brief.getTo()); // an invented address is replaced
    }

    @Test void addressesLoseBracketsQuotesAndPlaceholders() {
        assertEquals(java.util.List.of("a@b.c", "d@e.f"), com.assistant.mail.workflow.Planner.addresses(
                java.util.List.of("[a@b.c]", "<d@e.f>, \"a@b.c\"", "-", "[]", "none")));
    }
}

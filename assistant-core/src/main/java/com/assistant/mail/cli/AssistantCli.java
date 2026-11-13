package com.assistant.mail.cli;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.trace.TraceStep;
import com.assistant.mail.workflow.Turn;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Terminal front end: type a request, read the result. Drafts are saved, never sent. */
public final class AssistantCli {
    private static final String HELP = """
            Écrivez une demande (ex. « Résume le mail d'Ada », « Réponds à Ada »).
              /trace    étapes de la dernière demande (planner → rédacteur)
              /drafts   brouillons enregistrés (jamais envoyés)
              /inbox    mails de la boîte de démonstration
              /help     cette aide
              /quit     quitter""";

    private AssistantCli() {}

    public static void run(Assistant assistant, InputStream in, PrintStream out) throws IOException {
        out.println("Assistant mail — " + assistant.description());
        out.println("Tapez /help pour l'aide, /quit pour quitter.");
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        while (true) {
            out.print("> ");
            out.flush();
            String line = reader.readLine();
            if (line == null) break;
            line = line.strip();
            if (line.isEmpty()) continue;
            if (line.equals("/quit") || line.equals("/exit")) break;
            switch (line) {
                case "/help" -> out.println(HELP);
                case "/trace" -> trace(assistant, out);
                case "/drafts" -> drafts(assistant, out);
                case "/inbox" -> inbox(assistant, out);
                default -> {
                    try {
                        show(assistant.ask(line), out);
                    } catch (RuntimeException e) {
                        out.println("Erreur : " + e.getMessage());
                    }
                }
            }
        }
        out.println("Au revoir.");
    }

    static void show(Turn.Outcome o, PrintStream out) {
        for (String warning : o.warnings) out.println(warning);
        for (String chat : o.chats) out.println(chat);
        for (String refusal : o.refused) out.println("Refus : " + refusal);
        for (Question q : o.questions) {
            out.println("Question : " + q.getText());
            for (String option : q.getOptions()) out.println("  - " + option);
        }
        for (Summary s : o.summaries) out.println("Résumé :\n" + s.getBody());
        for (Draft d : o.drafts) {
            out.println("Brouillon #" + d.getId() + " (enregistré, non envoyé)");
            out.println("  À      : " + String.join(", ", d.getRecipients()));
            if (!d.getCc().isEmpty()) out.println("  Cc     : " + String.join(", ", d.getCc()));
            out.println("  Objet  : " + d.getSubject());
            out.println("  " + String.valueOf(d.getBody()).replace("\n", "\n  "));
        }
        for (String p : o.progress) out.println("Checklist : " + p);
    }

    private static void trace(Assistant assistant, PrintStream out) {
        Turn.Outcome last = assistant.last();
        if (last == null || last.trace == null || last.trace.getSteps().isEmpty()) {
            out.println("Aucune trace : faites d'abord une demande.");
            return;
        }
        for (TraceStep s : last.trace.getSteps()) {
            out.println("[" + s.getActor() + "] " + s.getAction()
                    + (s.getDetail().isBlank() ? "" : " — " + s.getDetail())
                    + (s.getError().isBlank() ? "" : "  !! " + s.getError()));
        }
    }

    private static void drafts(Assistant assistant, PrintStream out) {
        if (assistant.savedDrafts().isEmpty()) {
            out.println("Aucun brouillon.");
            return;
        }
        for (Draft d : assistant.savedDrafts()) {
            out.println("#" + d.getId() + " → " + String.join(", ", d.getRecipients()) + " : " + d.getSubject());
        }
    }

    private static void inbox(Assistant assistant, PrintStream out) {
        var mails = assistant.mail() instanceof com.assistant.mail.mailbox.InMemoryMailbox box
                ? box.all() : java.util.List.<Email>of();
        if (mails.isEmpty()) {
            out.println("Boîte non listable (boîte MCP : demandez au planner de chercher).");
            return;
        }
        for (Email e : mails) {
            out.println("#" + e.getId() + " " + (e.getSender() == null ? "?" : e.getSender().getEmail())
                    + " — " + e.getSubject());
        }
    }
}

package fr.mas.email.console;

import fr.mas.email.agent.Agent;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.SenderRole;
import fr.mas.email.routing.RoutedResponse;
import fr.mas.email.system.EmailAssistantSystem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Interface console minimale (human-in-the-loop).
 *
 * <p>L'utilisateur peut charger un email, visualiser l'agent choisi et la
 * proposition de reponse, puis la valider, la rejeter ou demander une
 * regeneration avec un commentaire.</p>
 */
public final class ConsoleApp {

    private final EmailAssistantSystem system;
    private final Scanner scanner = new Scanner(System.in);
    private List<EmailMessage> inbox = new ArrayList<>(SampleEmails.all());

    private EmailMessage currentEmail;
    private RoutedResponse currentRouted;

    public ConsoleApp(EmailAssistantSystem system) {
        this.system = system;
    }

    public void run() {
        printBanner();
        boolean running = true;
        while (running) {
            System.out.print("\n> ");
            String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "quit";
            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String argument = parts.length > 1 ? parts[1] : "";

            switch (command) {
                case "help" -> printHelp();
                case "agents" -> printAgents();
                case "sample", "samples" -> printSamples();
                case "inbox" -> fetchInbox();
                case "load" -> loadSample(argument);
                case "new" -> createEmail();
                case "regen", "regenerate" -> regenerate(argument);
                case "accept" -> accept();
                case "reject" -> reject();
                case "quit", "exit" -> running = false;
                case "" -> {
                }
                default -> System.out.println("Commande inconnue. Tapez 'help'.");
            }
        }
        System.out.println("Au revoir.");
    }

    private void printBanner() {
        System.out.println("======================================================");
        System.out.println(" Systeme multi-agents de traitement d'emails");
        System.out.println(" LLM   : " + system.llmDescription());
        System.out.println(" Agents: " + system.registry().size());
        System.out.println(" Mail  : " + (system.mailEnabled() ? "Outlook (Microsoft Graph)" : "desactive (mode console)"));
        System.out.println("======================================================");
        printHelp();
    }

    private void printHelp() {
        System.out.println("""
                Commandes :
                  agents          lister les agents disponibles
                  sample          lister les emails de demonstration
                  inbox           recuperer les derniers emails Outlook (Graph)
                  load <n>        charger et router l'email n
                  new             saisir un nouvel email
                  accept          valider la proposition (envoi Outlook si active)
                  reject          rejeter la proposition courante
                  regen <texte>   regenerer la proposition avec un commentaire
                  help            afficher cette aide
                  quit            quitter""");
    }

    private void printAgents() {
        for (Agent agent : system.registry().all()) {
            System.out.printf("  - %-15s %s%n", agent.id(), agent.description());
        }
    }

    private void printSamples() {
        if (inbox.isEmpty()) {
            System.out.println("Aucun email. Utilisez 'sample' ou 'inbox'.");
            return;
        }
        int index = 1;
        for (EmailMessage email : inbox) {
            System.out.printf("  [%d] %-28s | %s%n", index, email.senderLabel(), email.subject());
            index++;
        }
        System.out.println("Utilisez 'load <n>' pour en traiter un.");
    }

    private void fetchInbox() {
        if (!system.mailEnabled()) {
            System.out.println("Integration Outlook desactivee (outlook.enabled=false).");
            return;
        }
        System.out.println("Connexion a Outlook et recuperation des messages...");
        this.inbox = new ArrayList<>(system.fetchInbox(system.config().outlookFetchLimit()));
        System.out.println(inbox.size() + " message(s) recupere(s).");
        printSamples();
    }

    private void loadSample(String argument) {
        try {
            int index = Integer.parseInt(argument) - 1;
            if (index < 0 || index >= inbox.size()) {
                System.out.println("Indice invalide.");
                return;
            }
            process(inbox.get(index));
        } catch (NumberFormatException e) {
            System.out.println("Usage : load <n>");
        }
    }

    private void createEmail() {
        System.out.print("Adresse expediteur : ");
        String from = scanner.nextLine().trim();
        System.out.print("Nom expediteur : ");
        String name = scanner.nextLine().trim();
        System.out.print("Role (STUDENT/TEACHER/ADMINISTRATION/EXTERNAL/UNKNOWN) : ");
        String roleText = scanner.nextLine().trim().toUpperCase();
        System.out.print("Sujet : ");
        String subject = scanner.nextLine().trim();
        System.out.println("Corps (terminez par une ligne '.') :");
        StringBuilder body = new StringBuilder();
        while (scanner.hasNextLine()) {
            String bodyLine = scanner.nextLine();
            if (".".equals(bodyLine)) {
                break;
            }
            body.append(bodyLine).append("\n");
        }
        SenderRole role;
        try {
            role = SenderRole.valueOf(roleText);
        } catch (IllegalArgumentException e) {
            role = SenderRole.UNKNOWN;
        }
        process(new EmailMessage(from, name, subject, body.toString().strip(),
                Instant.now(), null, role));
    }

    private void process(EmailMessage email) {
        this.currentEmail = email;
        System.out.println("\n--- Routage et generation en cours... ---");
        this.currentRouted = system.process(email);
        displayCurrent();
    }

    private void regenerate(String comment) {
        if (currentEmail == null) {
            System.out.println("Aucun email courant. Utilisez 'load <n>' ou 'new'.");
            return;
        }
        if (comment.isBlank()) {
            System.out.println("Usage : regen <commentaire>");
            return;
        }
        this.currentRouted = system.regenerate(currentEmail, comment);
        System.out.println("\n--- Proposition regeneree ---");
        displayCurrent();
    }

    private void accept() {
        if (currentEmail == null || currentRouted == null) {
            System.out.println("Aucune proposition a valider.");
            return;
        }
        system.accept(currentEmail, currentRouted.response());
        if (system.mailEnabled() && currentEmail.externalId() != null) {
            System.out.println("Reponse envoyee via Outlook dans le fil d'origine.");
        } else {
            System.out.println("Proposition validee et enregistree comme envoyee.");
        }
    }

    private void reject() {
        if (currentEmail == null || currentRouted == null) {
            System.out.println("Aucune proposition a rejeter.");
            return;
        }
        system.reject(currentEmail);
        System.out.println("Proposition rejetee.");
    }

    private void displayCurrent() {
        System.out.printf("%nAgent selectionne : %s (categorie=%s, confiance=%.2f)%n",
                currentRouted.decision().targetAgentId(),
                currentRouted.decision().category(),
                currentRouted.decision().confidence());
        System.out.println("Justification     : " + currentRouted.decision().rationale());
        System.out.println("\n----------- PROPOSITION DE REPONSE -----------");
        System.out.println(currentRouted.response().content());
        System.out.println("----------------------------------------------");
        System.out.println("Actions : accept | reject | regen <commentaire>");
    }
}

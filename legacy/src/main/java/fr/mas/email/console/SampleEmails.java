package fr.mas.email.console;

import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.SenderRole;

import java.time.Instant;
import java.util.List;

/**
 * Jeux d'emails de demonstration pour les experiences.
 */
public final class SampleEmails {

    private SampleEmails() {
    }

    public static List<EmailMessage> all() {
        Instant now = Instant.now();
        return List.of(
                new EmailMessage("etudiant.martin@univ.fr", "Alice Martin",
                        "Question sur le projet de Systemes Multi-Agents",
                        "Bonjour, je suis un peu perdue sur les attendus du projet. "
                                + "Faut-il rendre un rapport en plus du code ? Et quelle est la date limite ? Merci d'avance.",
                        now, "thread-etu-1", SenderRole.STUDENT),

                new EmailMessage("collegue.bernard@univ.fr", "Dr Bernard",
                        "Reunion de coordination SuperMASProject",
                        "Bonjour, peux-tu confirmer ta disponibilite pour une reunion la semaine prochaine ? "
                                + "Il faut preparer la soumission de l'article de position. "
                                + "Je propose mardi. Cordialement.",
                        now, "thread-ens-1", SenderRole.TEACHER),

                new EmailMessage("rh@univ.fr", "Service RH",
                        "Ordre de mission a completer",
                        "Bonjour, votre demande de mission pour Barcelone est incomplete. "
                                + "Merci de fournir l'objet, le financement et les dates exactes avant traitement.",
                        now, "thread-adm-1", SenderRole.ADMINISTRATION),

                new EmailMessage("partenaire@bcn.edu", "Pr Sanchez",
                        "Follow-up SuperMASProject",
                        "Hi, thanks for the update. Could you send the latest prototype documentation "
                                + "before the next milestone? Best regards.",
                        now, "thread-ens-1", SenderRole.EXTERNAL),

                new EmailMessage("inconnu@random.com", "Someone",
                        "Question diverse",
                        "Bonjour, pourriez-vous me dire qui contacter pour un sujet dont je ne connais pas la nature ?",
                        now, "thread-amb-1", SenderRole.UNKNOWN)
        );
    }
}

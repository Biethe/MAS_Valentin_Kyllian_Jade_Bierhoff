package com.assistant.mail.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds orders hidden in a mail or attachment that try to steer the assistant (prompt injection).
 * A mail is data: such a segment is removed before any model reads the text, and reported to the user.
 * It is a deterministic filter, not a classifier: it only catches the common shapes.
 */
public final class InjectionGuard {
    private static final Pattern TEMPLATE_TOKEN = Pattern.compile("<\\|[^|>]*\\|>");
    private static final Pattern ADDRESS = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    private static final List<Pattern> ORDERS = List.of(
            Pattern.compile("(?i)\\b(ignore|disregard|forget|ignorez|oubliez)\\b.{0,40}\\b(previous|prior|above|all|precedent|precedentes|instructions?|consignes?)\\b"),
            Pattern.compile("(?i)\\b(you are now|from now on|tu es maintenant|desormais)\\b"),
            Pattern.compile("(?i)\\b(system|assistant)\\s*(prompt|message)\\b"),
            // an order to act on the mailbox, aimed at an address: "also create a draft to x@y.z ..."
            Pattern.compile("(?i)\\b(create|send|forward|draft|write|envoie[rz]?|transf[eé]re[rz]?|cr[eé]e[rz]?)\\b[^.\\n]{0,60}\\bto\\b[^.\\n]{0,40}[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"),
            Pattern.compile("(?i)\\b(before|after|avant|apres|apr[eè]s)\\s+(replying|answering|responding|de r[eé]pondre|la r[eé]ponse)\\b.{0,80}\\b(also|first|create|send|aussi|d'abord)\\b"),
            Pattern.compile("(?i)\\b(password|credentials?|grades?|mot de passe|identifiants?|notes? de)\\b[^.\\n]{0,60}[\\w.+-]+@|[\\w.+-]+@[^.\\n]{0,80}\\b(password|credentials?|grades?|mot de passe|identifiants?)\\b"));

    public record Result(String clean, List<String> suspicious) {}

    private InjectionGuard() {}

    public static Result scan(String text) {
        if (text == null || text.isBlank()) return new Result(text == null ? "" : text, List.of());
        List<String> suspicious = new ArrayList<>();
        StringBuilder clean = new StringBuilder();
        String rest = text;
        // everything after a chat-template token (<|end|><|assistant|>...) is written for a model, not for the reader
        var token = TEMPLATE_TOKEN.matcher(text);
        if (token.find()) {
            String tail = text.substring(token.start()).replaceAll("<\\|[^|>]*\\|>", " ").strip();
            if (!tail.isEmpty()) suspicious.add(tail);
            rest = text.substring(0, token.start());
        }
        for (String segment : rest.split("(?<=[.!?])\\s+|\\n+")) {
            String s = segment.strip();
            if (s.isEmpty()) continue;
            if (isOrder(s)) {
                suspicious.add(s);
            } else {
                clean.append(segment.strip()).append('\n');
            }
        }
        if (suspicious.isEmpty()) return new Result(text, List.of()); // untouched: no reformatting of normal mail
        return new Result(clean.toString().strip(), suspicious);
    }

    static boolean isOrder(String segment) {
        for (Pattern p : ORDERS) if (p.matcher(segment).find()) return true;
        return false;
    }

    /** Short, single-line form of a flagged segment for messages. */
    public static String quote(String segment) {
        String one = segment.replaceAll("\\s+", " ").strip();
        return one.length() > 220 ? one.substring(0, 217) + "..." : one;
    }

    static boolean mentionsAddress(String text) {
        return ADDRESS.matcher(text).find();
    }
}

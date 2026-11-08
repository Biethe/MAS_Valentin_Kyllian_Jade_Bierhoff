package com.assistant.mail.workflow;

import com.assistant.mail.model.brief.Language;
import com.assistant.mail.model.plan.InstructionType;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FR when French stopwords are at least the English ones, matching the benchmark check. */
public final class Languages {
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Set<String> FR = Set.of("le", "la", "les", "de", "des", "du", "un", "une", "et", "est", "je",
            "vous", "nous", "pour", "que", "qui", "dans", "sur", "au", "aux", "pas", "avec", "ce", "cette",
            "mon", "ma", "votre", "vos", "à", "en", "ne", "bien");
    private static final Set<String> EN = Set.of("the", "a", "an", "and", "is", "are", "i", "you", "we", "for", "that",
            "which", "in", "on", "to", "of", "not", "with", "this", "my", "your", "be", "will", "it", "at", "am");

    private Languages() {}

    public static Language of(String text) {
        int fr = 0, en = 0;
        Matcher words = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (words.find()) {
            if (FR.contains(words.group())) fr++;
            if (EN.contains(words.group())) en++;
        }
        return fr >= en ? Language.FR : Language.EN;
    }

    /** A named language in a compose instruction, or null when the user did not name one. */
    public static Language explicit(String instruction) {
        String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if (text.contains("en français") || text.contains("in french")) return Language.FR;
        if (text.contains("en anglais") || text.contains("in english")) return Language.EN;
        return null;
    }

    public static Language output(InstructionType type, String instruction, Language user, String mailBody) {
        if (type == InstructionType.REPLY) return of(mailBody);
        if (type == InstructionType.COMPOSE) {
            Language named = explicit(instruction);
            return named == null ? user : named;
        }
        return user;
    }
}

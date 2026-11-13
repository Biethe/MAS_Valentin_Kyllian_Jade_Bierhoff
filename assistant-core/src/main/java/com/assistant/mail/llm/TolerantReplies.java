package com.assistant.mail.llm;

import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps a model so small formatting slips do not break the workflows: code fences are dropped, and a tool
 * call written as {@code search_mail("Ada")} becomes the block format the planner parses.
 * It changes the shape of a reply, never its content.
 */
public final class TolerantReplies implements PlannerAgentClient, DrafterAgentClient, SummarizerAgentClient {
    private static final Pattern CALL = Pattern.compile(
            "(search_mail|read_mail|read_thread)\\s*\\(\\s*(?:\\w+\\s*=\\s*)?[\"'`]?([^\"'`)]*)[\"'`]?\\s*\\)");

    private final PlannerAgentClient planner;
    private final DrafterAgentClient drafter;
    private final SummarizerAgentClient summarizer;

    public TolerantReplies(PlannerAgentClient planner, DrafterAgentClient drafter, SummarizerAgentClient summarizer) {
        this.planner = planner;
        this.drafter = drafter;
        this.summarizer = summarizer;
    }

    /** The same model behind all three ports. */
    public static TolerantReplies around(OllamaChat chat) {
        return new TolerantReplies(chat, chat, chat);
    }

    @Override
    public String complete(String system, String user) {
        throw new UnsupportedOperationException("use the typed ports");
    }

    public PlannerAgentClient planner() {
        return (system, user) -> normalizeFor(system, user, planner.complete(system, user));
    }

    public DrafterAgentClient drafter() {
        return (system, user) -> draftBlocks(system, normalize(system, drafter.complete(system, user)));
    }

    public SummarizerAgentClient summarizer() {
        return (system, user) -> normalize(system, summarizer.complete(system, user));
    }

    static String normalizeFor(String system, String user, String reply) {
        String text = normalize(system, reply);
        if (PlannerSystemPrompt.READ.equals(system) && !hasFact(text)) return wholeDocument(user);
        if (PlannerSystemPrompt.SPLIT.equals(system) && !Pattern.compile("(?im)^\\s*body\\s*:").matcher(text).find()) {
            // not split: the whole query is one instruction
            String query = user == null ? "" : user.strip();
            return query.isEmpty() ? "" : "body:\n" + query;
        }
        if (PlannerSystemPrompt.LOCATE.equals(system)) return locateBlocks(text);
        if (PlannerSystemPrompt.ROUTE.equals(system) && !Pattern.compile("(?im)^\\s*type\\s*:").matcher(text).find()) {
            return routeByKeywords(user);
        }
        return text;
    }

    /** "1" or "m1, m2" instead of "email:\n1": bare mail ids become email blocks. */
    static String locateBlocks(String text) {
        if (Pattern.compile("(?im)^\\s*(email|thread|attachment|userFile)\\s*:").matcher(text).find()) return text;
        if (text.isBlank() || text.strip().equalsIgnoreCase("none")) return text;
        Matcher m = Pattern.compile("\\b[mM]?(\\d+)\\b").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (out.length() > 0) out.append("\n\n");
            out.append("email:\n").append(m.group(1));
        }
        return out.length() == 0 ? text : out.toString();
    }

    /** A model that refuses or rambles instead of routing: decide from the words of the instruction. */
    static String routeByKeywords(String user) {
        String instruction = user == null ? "" : user;
        int start = instruction.indexOf("instruction:");
        if (start >= 0) instruction = instruction.substring(start + "instruction:".length());
        int rules = instruction.indexOf("\nrules:");
        if (rules >= 0) instruction = instruction.substring(0, rules);
        String t = java.text.Normalizer.normalize(instruction.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String type;
        if (t.matches("(?s).*(resum|summar|synthes|recap).*")) type = "SUMMARIZE";
        else if (t.matches("(?s).*(repon|reply|answer|reagi).*")) type = "REPLY";
        else if (t.matches("(?s).*(ecri|redig|compos|write|draft|envoi|send|prepar).*")) type = "COMPOSE";
        else if (t.strip().matches("(?s)(bonjour|salut|hello|hi|hey|merci|thanks|thank you)\\b.*")) type = "CHAT";
        else return "type:\nREPLY\n\nclear:\nno";
        return "type:\n" + type + "\n\nclear:\nyes";
    }

    /** True when the reply holds at least one real {@code body:} fact (not an echoed template). */
    static boolean hasFact(String text) {
        Matcher m = Pattern.compile("(?im)^\\s*body:\\s*(.*)$").matcher(text);
        while (m.find()) {
            String first = m.group(1).strip();
            if (first.isEmpty()) {
                int end = m.end();
                first = text.substring(Math.min(end, text.length())).strip().lines().findFirst().orElse("");
            }
            if (!first.isEmpty() && !first.contains("<one fact>")) return true;
        }
        return false;
    }

    /** Fallback when the model returns no usable fact: the document itself is the one fact. */
    static String wholeDocument(String user) {
        String doc = user == null ? "" : user;
        int mail = doc.lastIndexOf("\n\nmail:\n");
        if (mail >= 0) {
            doc = doc.substring(mail + "\n\nmail:\n".length());
        } else {
            int file = doc.indexOf("\nfile:");
            if (file >= 0) {
                int nl = doc.indexOf('\n', file + 1);
                doc = nl < 0 ? "" : doc.substring(nl + 1);
            }
        }
        doc = doc.strip();
        return doc.isEmpty() ? "" : "body:\n" + doc + "\n\nkind:\nINFORMATIVE";
    }

    static String normalize(String system, String reply) {
        String text = unfence(reply == null ? "" : reply);
        if (PlannerSystemPrompt.TOOLS.equals(system)) text = toBlocks(text);
        return text;
    }

    private static final Pattern DRAFT_KEY = Pattern.compile(
            "(?im)^[ \\t]*(recipients|cc|bcc|subject|body)[ \\t]*:[ \\t]*(.*?)[ \\t]*$");

    /** The drafter's blocks put the value on the next line; a model often writes {@code subject: Hello}. */
    static String draftBlocks(String system, String text) {
        if (!com.assistant.mail.prompt.DrafterSystemPrompt.TEXT.equals(system)
                && !com.assistant.mail.prompt.DrafterSystemPrompt.REWRITE.equals(system)) return text;
        Matcher m = DRAFT_KEY.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(
                    m.group(1).toLowerCase() + ":" + (m.group(2).isEmpty() ? "" : "\n" + m.group(2))));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String unfence(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int firstBreak = t.indexOf('\n');
            t = firstBreak < 0 ? "" : t.substring(firstBreak + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.strip();
    }

    private static final Pattern KEYED = Pattern.compile("(?im)^[ \\t]*(query|id|threadId)[ \\t]*:[ \\t]*(\\S.*)$");

    static String toBlocks(String text) {
        if (Pattern.compile("(?im)^\\s*name\\s*:").matcher(text).find()) return text;
        Matcher m = CALL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            append(out, m.group(1), m.group(2).strip());
        }
        if (out.length() == 0) {
            // the model echoed the template: "search_mail|read_mail|read_thread / query: Ada / id: / done: yes"
            Matcher k = KEYED.matcher(text);
            while (k.find()) {
                String key = k.group(1).toLowerCase();
                String value = k.group(2).strip();
                if (value.isEmpty() || value.startsWith("<")) continue;
                append(out, key.equals("query") ? "search_mail" : key.equals("id") ? "read_mail" : "read_thread", value);
            }
        }
        return out.length() == 0 ? text : out.toString();
    }

    private static void append(StringBuilder out, String name, String arg) {
        String key = name.equals("search_mail") ? "query" : name.equals("read_mail") ? "id" : "threadId";
        if (out.length() > 0) out.append("\n\n");
        out.append("name:\n").append(name).append("\n\n").append(key).append(":\n").append(arg);
    }
}

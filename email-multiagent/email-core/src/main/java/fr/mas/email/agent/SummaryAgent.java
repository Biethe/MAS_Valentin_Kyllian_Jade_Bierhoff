package fr.mas.email.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMException;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;

import java.util.List;

/**
 * Summarizes an email and, when the content needs a reply, pre-generates a
 * draft. The LLM is asked to return a JSON object with the summary, the
 * needs_reply flag, and the draft.
 */
public final class SummaryAgent {

    private final LLMClient llmClient;
    private final ContextProvider contextProvider;
    private final PromptRepository promptRepository;
    private final RecipientKnowledgeBase knowledgeBase;
    private final ObjectMapper mapper = new ObjectMapper();

    public SummaryAgent(LLMClient llmClient,
                        ContextProvider contextProvider,
                        PromptRepository promptRepository) {
        this(llmClient, contextProvider, promptRepository, null);
    }

    public SummaryAgent(LLMClient llmClient,
                        ContextProvider contextProvider,
                        PromptRepository promptRepository,
                        RecipientKnowledgeBase knowledgeBase) {
        this.llmClient = llmClient;
        this.contextProvider = contextProvider;
        this.promptRepository = promptRepository;
        this.knowledgeBase = knowledgeBase;
    }

    public SummaryResult summarize(EmailMessage email) {
        String prompt = promptRepository.load("summarizer");
        String context = contextProvider.contextFor(EmailCategory.GENERIC, email.getSubject() + " " + email.getBody());
        String system = prompt;
        if (!context.isBlank()) {
            system = system + "\n\n## Context\n\n" + context;
        }
        String contact = contactContext(email);
        if (!contact.isBlank()) {
            system = system + "\n\n## Contact knowledge\n\n" + contact;
        }

        List<ChatMessage> messages = List.of(
                ChatMessage.system(system),
                ChatMessage.user("Email:\n" + email
                        + "\n\nReturn a JSON object with fields: summary, needs_reply, draft."));

        try {
            String raw = llmClient.complete(messages);
            SummaryResult result = parse(raw);
            if (!result.needsReply() && looksLikeRequest(result.summary(), email)) {
                return new SummaryResult(result.summary(), true, generateDraft(email));
            }
            if (result.needsReply() && result.draft().isBlank()) {
                return new SummaryResult(result.summary(), true, generateDraft(email));
            }
            return result;
        } catch (LLMException e) {
            return new SummaryResult("LLM error: " + e.getMessage(), false, "");
        }
    }

    /**
     * A safety net for weak local models that under-classify requests. The
     * LLM decides first; this only overrides an explicit "no reply" when the
     * summary or the email clearly asks for a response.
     */
    private static boolean looksLikeRequest(String summary, EmailMessage email) {
        String text = (summary + " " + email.getSubject()).toLowerCase();
        String[] markers = {
                "please", "request", "requested", "deadline", "due", "send",
                "reply", "response", "meeting", "confirm", "urgent", "action",
                "demande", "envoyer", "répondre", "réponse", "rendez-vous",
                "merci", "confirmer", "?"
        };
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String generateDraft(EmailMessage email) {
        String system = "You are a reply assistant. Write a short, polite reply in the same language as the email. "
                + "Do not invent facts. Do not send the email. Return only the reply text.";
        String contact = contactContext(email);
        if (!contact.isBlank()) {
            system = system + "\n\n## Contact knowledge\n\n" + contact;
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(system),
                ChatMessage.user("Email:\n" + email));
        try {
            return llmClient.complete(messages).trim();
        } catch (LLMException e) {
            return "";
        }
    }

    private String contactContext(EmailMessage email) {
        return knowledgeBase == null ? "" : knowledgeBase.promptContextFor(email.getSender());
    }

    private SummaryResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SummaryResult("", false, "");
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(raw.substring(start, end + 1));
                return new SummaryResult(
                        node.path("summary").asText(""),
                        node.path("needs_reply").asBoolean(false),
                        node.path("draft").asText(""));
            }
        } catch (Exception ignored) {
            // Fall through to the raw text.
        }
        return parseText(raw);
    }

    /**
     * Fallback for LLMs that return labelled text instead of JSON. It extracts
     * the summary, the reply flag and the draft from the labels.
     */
    private SummaryResult parseText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new SummaryResult("", false, "");
        }

        String summary = text;
        boolean needsReply = false;
        String draft = "";

        int summaryIdx = indexOfIgnoreCase(text, "summary:");
        String needsMarker = "needs reply:";
        int needsIdx = indexOfIgnoreCase(text, needsMarker);
        if (needsIdx < 0) {
            needsMarker = "needs_reply:";
            needsIdx = indexOfIgnoreCase(text, needsMarker);
        }
        int draftIdx = indexOfIgnoreCase(text, "draft:");

        if (summaryIdx >= 0) {
            int end = firstPositive(needsIdx, draftIdx, text.length());
            summary = text.substring(summaryIdx + "summary:".length(), end).trim();
        }

        if (needsIdx >= 0) {
            int end = firstPositive(draftIdx, text.length());
            String flag = text.substring(needsIdx + needsMarker.length(), end).trim();
            needsReply = flag.toLowerCase().startsWith("true");
        }

        if (draftIdx >= 0) {
            draft = text.substring(draftIdx + "draft:".length()).trim();
            draft = stripQuotes(draft);
        }

        if (needsReply && draft.isEmpty()) {
            needsReply = false;
        }

        return new SummaryResult(summary, needsReply, draft);
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase().indexOf(needle.toLowerCase());
    }

    private static int firstPositive(int... values) {
        int best = Integer.MAX_VALUE;
        for (int value : values) {
            if (value >= 0 && value < best) {
                best = value;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static String stripQuotes(String value) {
        String text = value.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.trim();
    }
}

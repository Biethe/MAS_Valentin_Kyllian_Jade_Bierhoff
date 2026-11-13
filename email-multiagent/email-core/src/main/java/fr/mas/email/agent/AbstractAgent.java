package fr.mas.email.agent;

import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.llm.ChatMessage;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMException;
import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailCategory;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for specialized agents. It owns the system prompt, the document
 * context and the conversation history, and delegates generation to the LLM.
 */
public abstract class AbstractAgent implements Agent {

    private final String name;
    private final EmailCategory category;
    private final String promptName;
    private final LLMClient llmClient;
    private final ContextProvider contextProvider;
    private final PromptRepository promptRepository;
    private final RecipientKnowledgeBase knowledgeBase;

    protected AbstractAgent(String name,
                            EmailCategory category,
                            String promptName,
                            LLMClient llmClient,
                            ContextProvider contextProvider,
                            PromptRepository promptRepository) {
        this(name, category, promptName, llmClient, contextProvider, promptRepository, null);
    }

    protected AbstractAgent(String name,
                            EmailCategory category,
                            String promptName,
                            LLMClient llmClient,
                            ContextProvider contextProvider,
                            PromptRepository promptRepository,
                            RecipientKnowledgeBase knowledgeBase) {
        this.name = name;
        this.category = category;
        this.promptName = promptName;
        this.llmClient = llmClient;
        this.contextProvider = contextProvider;
        this.promptRepository = promptRepository;
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String categoryName) {
        return category.name().equals(categoryName);
    }

    @Override
    public AgentResponse handle(EmailMessage email, List<String> conversationHistory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(buildSystemPrompt(email)));
        for (String previous : conversationHistory) {
            messages.add(ChatMessage.assistant(previous));
        }
        messages.add(ChatMessage.user(buildUserPrompt(email)));

        try {
            return new AgentResponse(name, category, llmClient.complete(messages));
        } catch (LLMException e) {
            return new AgentResponse(name, category, "LLM error: " + e.getMessage());
        }
    }

    private String buildSystemPrompt(EmailMessage email) {
        StringBuilder prompt = new StringBuilder(promptRepository.load(promptName));

        String query = email.getSubject() + " " + email.getBody();
        String context = contextProvider.contextFor(category, query);
        if (!context.isBlank()) {
            prompt.append("\n\n## Context\n\n").append(context);
        }

        if (knowledgeBase != null) {
            String contact = knowledgeBase.promptContextFor(email.getSender());
            if (!contact.isBlank()) {
                prompt.append("\n\n## Contact knowledge\n\n").append(contact);
            }
        }
        return prompt.toString();
    }

    private String buildUserPrompt(EmailMessage email) {
        return "Email received:\n\n" + email + "\n\nDraft a reply. Do not send it.";
    }
}

package fr.mas.email.system;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.agent.RouterAgent;
import fr.mas.email.config.AppConfig;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMClientFactory;
import fr.mas.email.mail.MailClient;
import fr.mas.email.mail.MailClientFactory;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.model.AgentResponse;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.prompt.PromptRepository;
import fr.mas.email.routing.KeywordRouterStrategy;
import fr.mas.email.routing.LlmRouterStrategy;
import fr.mas.email.routing.RoutedResponse;

/**
 * Facade du systeme multi-agents.
 *
 * <p>Assemble la couche LLM, les agents, la memoire et le coordinateur, et
 * expose une API simple a l'interface utilisateur (pattern <em>Facade</em>).</p>
 */
public final class EmailAssistantSystem {

    private final AppConfig config;
    private final LLMClient llmClient;
    private final MailClient mailClient;
    private final AgentRegistry registry;
    private final RouterAgent routerAgent;
    private final ConversationMemory memory;

    public EmailAssistantSystem(AppConfig config) {
        this(config, MailClientFactory.create(config));
    }

    public EmailAssistantSystem(AppConfig config, MailClient mailClient) {
        this.config = config;
        this.llmClient = LLMClientFactory.create(config);
        this.mailClient = mailClient;

        PromptRepository promptRepository = new PromptRepository(config.promptsDirectory());
        AgentFactory agentFactory = AgentFactory.fromConfig(llmClient, config);
        this.registry = agentFactory.buildRegistry();

        KeywordRouterStrategy keywordStrategy = new KeywordRouterStrategy();
        LlmRouterStrategy llmStrategy =
                new LlmRouterStrategy(llmClient, promptRepository.load("router"), keywordStrategy);

        this.routerAgent = new RouterAgent(
                registry, llmStrategy, config.routerConfidenceThreshold(), config.memoryMaxMessages());
        this.memory = new ConversationMemory();
    }

    /** Analyse un email, le route et genere une proposition de reponse. */
    public RoutedResponse process(EmailMessage email) {
        memory.rememberEmail(email);
        RoutedResponse routed = routerAgent.process(email, memory);
        memory.rememberProposal(email.threadId(), routed.response().content());
        return routed;
    }

    /** Regénère une proposition en tenant compte du retour de l'utilisateur. */
    public RoutedResponse regenerate(EmailMessage email, String userComment) {
        RoutedResponse routed = routerAgent.regenerate(email, memory, userComment);
        memory.rememberProposal(email.threadId(), routed.response().content());
        return routed;
    }

    /** Valide la proposition : l'email est considere comme envoye. */
    public void accept(EmailMessage email, AgentResponse response) {
        if (mailClient.isEnabled() && email.externalId() != null) {
            mailClient.sendReply(email, response.content());
        }
        memory.rememberSent(email.threadId(), response.content());
    }

    /** Rejette la proposition. */
    public void reject(EmailMessage email) {
        memory.rememberProposal(email.threadId(), "[Proposition rejetee par l'utilisateur]");
    }

    /** Recupere les derniers emails de la boite Outlook. */
    public java.util.List<EmailMessage> fetchInbox(int limit) {
        return mailClient.fetchInbox(limit);
    }

    public boolean mailEnabled() {
        return mailClient.isEnabled();
    }

    public MailClient mailClient() {
        return mailClient;
    }

    public AgentRegistry registry() {
        return registry;
    }

    public ConversationMemory memory() {
        return memory;
    }

    public AppConfig config() {
        return config;
    }

    public String llmDescription() {
        return config.llmProvider() + " / " + llmClient.modelName() + " @ " + config.llmBaseUrl();
    }
}

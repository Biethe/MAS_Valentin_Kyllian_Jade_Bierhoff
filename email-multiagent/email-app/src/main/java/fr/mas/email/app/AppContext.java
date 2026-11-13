package fr.mas.email.app;

import fr.mas.email.agent.AgentFactory;
import fr.mas.email.agent.AgentRegistry;
import fr.mas.email.agent.RouterAgent;
import fr.mas.email.agent.SummaryAgent;
import fr.mas.email.audit.AuditLog;
import fr.mas.email.config.AppConfig;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.EmbeddingClient;
import fr.mas.email.context.EmbeddingClientFactory;
import fr.mas.email.context.FileContextProvider;
import fr.mas.email.context.RagContextProvider;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.feedback.FeedbackStore;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.llm.LLMClientFactory;
import fr.mas.email.mail.MailClient;
import fr.mas.email.mail.MailClientFactory;
import fr.mas.email.mail.MailSender;
import fr.mas.email.mail.MailSenderFactory;
import fr.mas.email.memory.ConversationMemory;
import fr.mas.email.metrics.MetricsService;
import fr.mas.email.prompt.PromptRepository;
import fr.mas.email.routing.FeedbackRouter;
import fr.mas.email.routing.KeywordRouterStrategy;
import fr.mas.email.routing.RouterFactory;
import fr.mas.email.routing.RouterStrategy;
import fr.mas.email.security.SecurityAgent;
import fr.mas.email.system.EmailAssistantSystem;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Composition root. It is the only place where concrete implementations are
 * chosen and wired together. Every other module receives its collaborators
 * through a constructor, so it can be reused or replaced in isolation when
 * this project is merged with another one.
 *
 * <p>To integrate the system elsewhere, build the pieces you need directly, or
 * replace one factory here (for example {@link MailClientFactory}) without
 * touching the agents, the routing or the HTTP layer.</p>
 */
public final class AppContext {

    private final AppConfig config;
    private final Path baseDir;
    private final LLMClient llmClient;
    private final ContextProvider contextProvider;
    private final PromptRepository promptRepository;
    private final RouterStrategy routerStrategy;
    private final RouterStrategy inboxRouter;
    private final EmailAssistantSystem system;
    private final SummaryAgent summaryAgent;
    private final MailClient mailClient;
    private final MailSender mailSender;
    private final RecipientKnowledgeBase knowledgeBase;
    private final AuditLog auditLog;
    private final FeedbackStore feedbackStore;
    private final MetricsService metricsService;
    private final SecurityAgent securityAgent;
    private final int mailTop;

    public static AppContext create() {
        return new AppContext(AppConfig.load());
    }

    public AppContext(AppConfig config) {
        this(config, Path.of("."));
    }

    public AppContext(AppConfig config, Path baseDir) {
        this.config = Objects.requireNonNull(config, "config");
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");

        this.llmClient = LLMClientFactory.create(config);
        this.contextProvider = createContextProvider(config);
        this.promptRepository = new PromptRepository(config.get("ui.language", "en"));
        this.routerStrategy = RouterFactory.create(config, llmClient);
        this.securityAgent = new SecurityAgent();
        this.knowledgeBase = new RecipientKnowledgeBase(resolve("context", "recipients.json"));

        this.feedbackStore = new FeedbackStore(resolve("logs"));
        RouterStrategy feedbackRouter = new FeedbackRouter(routerStrategy, feedbackStore);
        this.inboxRouter = new FeedbackRouter(
                new KeywordRouterStrategy(config.getDouble("routing.threshold", 0.5)),
                feedbackStore);

        AgentRegistry registry = AgentFactory.createDefaultRegistry(
                llmClient,
                contextProvider,
                promptRepository,
                knowledgeBase);
        RouterAgent router = new RouterAgent("RouterAgent", feedbackRouter, registry);
        this.system = new EmailAssistantSystem(
                router,
                new ConversationMemory(config.getInt("memory.max.entries", 10)),
                "GenericAgent",
                securityAgent);

        this.summaryAgent = new SummaryAgent(llmClient, contextProvider, promptRepository, knowledgeBase);
        this.mailClient = MailClientFactory.create(config);
        this.mailSender = MailSenderFactory.create(config);
        this.auditLog = new AuditLog(resolve("logs"));
        this.metricsService = new MetricsService(auditLog, feedbackStore);
        this.mailTop = config.getInt("mail.top", 50);
    }

    private ContextProvider createContextProvider(AppConfig config) {
        boolean rag = config.get("rag.enabled", "true").equalsIgnoreCase("true");
        if (rag) {
            EmbeddingClient embedding = EmbeddingClientFactory.create(config);
            return new RagContextProvider(resolve("context"), embedding,
                    config.getInt("rag.topK", 3));
        }
        return new FileContextProvider(resolve("context"));
    }

    private Path resolve(String first, String... more) {
        return baseDir.resolve(Path.of(first, more));
    }

    public AppConfig config() {
        return config;
    }

    public Path baseDir() {
        return baseDir;
    }

    public LLMClient llmClient() {
        return llmClient;
    }

    public ContextProvider contextProvider() {
        return contextProvider;
    }

    public PromptRepository promptRepository() {
        return promptRepository;
    }

    public RouterStrategy routerStrategy() {
        return routerStrategy;
    }

    public RouterStrategy inboxRouter() {
        return inboxRouter;
    }

    public EmailAssistantSystem system() {
        return system;
    }

    public SummaryAgent summaryAgent() {
        return summaryAgent;
    }

    public MailClient mailClient() {
        return mailClient;
    }

    public MailSender mailSender() {
        return mailSender;
    }

    public RecipientKnowledgeBase knowledgeBase() {
        return knowledgeBase;
    }

    public AuditLog auditLog() {
        return auditLog;
    }

    public FeedbackStore feedbackStore() {
        return feedbackStore;
    }

    public MetricsService metricsService() {
        return metricsService;
    }

    public SecurityAgent securityAgent() {
        return securityAgent;
    }

    public int mailTop() {
        return mailTop;
    }
}

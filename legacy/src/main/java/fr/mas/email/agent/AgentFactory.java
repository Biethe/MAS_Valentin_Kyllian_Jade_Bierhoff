package fr.mas.email.agent;

import fr.mas.email.agent.specialized.AdministrationAgent;
import fr.mas.email.agent.specialized.GenericAgent;
import fr.mas.email.agent.specialized.StudentAgent;
import fr.mas.email.agent.specialized.TeacherAgent;
import fr.mas.email.config.AppConfig;
import fr.mas.email.context.ContextProvider;
import fr.mas.email.context.FileContextProvider;
import fr.mas.email.llm.LLMClient;
import fr.mas.email.prompt.PromptRepository;

import java.nio.file.Path;

/**
 * Construit les agents par defaut et les enregistre (pattern <em>Factory</em>).
 *
 * <p>Ajouter un nouvel agent specialise revient a ajouter une classe et une
 * ligne ici : le reste du systeme n'est pas impacte.</p>
 */
public final class AgentFactory {

    private static final int CONTEXT_MAX_CHARS = 12_000;

    private final LLMClient llmClient;
    private final PromptRepository promptRepository;
    private final Path contextDirectory;
    private final int maxHistoryMessages;

    public AgentFactory(LLMClient llmClient,
                        PromptRepository promptRepository,
                        Path contextDirectory,
                        int maxHistoryMessages) {
        this.llmClient = llmClient;
        this.promptRepository = promptRepository;
        this.contextDirectory = contextDirectory;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public static AgentFactory fromConfig(LLMClient llmClient, AppConfig config) {
        return new AgentFactory(
                llmClient,
                new PromptRepository(config.promptsDirectory()),
                config.contextDirectory(),
                config.memoryMaxMessages());
    }

    public AgentRegistry buildRegistry() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(new StudentAgent(llmClient, prompt("student"), context("student"), maxHistoryMessages));
        registry.register(new TeacherAgent(llmClient, prompt("teacher"), context("teacher"), maxHistoryMessages));
        registry.register(new AdministrationAgent(llmClient, prompt("administration"), context("administration"), maxHistoryMessages));
        registry.register(new GenericAgent(llmClient, prompt("generic"), context("generic"), maxHistoryMessages));
        return registry;
    }

    private String prompt(String agentId) {
        return promptRepository.load(agentId);
    }

    private ContextProvider context(String agentId) {
        return new FileContextProvider(contextDirectory, agentId, CONTEXT_MAX_CHARS);
    }
}

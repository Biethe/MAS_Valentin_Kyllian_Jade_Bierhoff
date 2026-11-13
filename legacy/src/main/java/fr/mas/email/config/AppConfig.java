package fr.mas.email.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration centrale de l'application.
 *
 * <p>Les valeurs sont chargees depuis {@code config.properties} (classpath) puis
 * peuvent etre surchargees par des variables d'environnement ou des proprietes
 * systeme, ce qui permet de changer de fournisseur LLM sans recompiler.</p>
 */
public final class AppConfig {

    private static final String DEFAULT_RESOURCE = "/config.properties";

    private final Properties properties;

    private AppConfig(Properties properties) {
        this.properties = properties;
    }

    public static AppConfig load() {
        return load(DEFAULT_RESOURCE);
    }

    public static AppConfig load(String classpathResource) {
        Properties properties = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream(classpathResource)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de charger " + classpathResource, e);
        }
        overrideFromEnvironment(properties);
        return new AppConfig(properties);
    }

    private static void overrideFromEnvironment(Properties properties) {
        for (String key : properties.stringPropertyNames().toArray(new String[0])) {
            String envKey = key.toUpperCase().replace('.', '_');
            String value = System.getenv(envKey);
            if (value == null) {
                value = System.getProperty(key);
            }
            if (value != null && !value.isBlank()) {
                properties.setProperty(key, value);
            }
        }
    }

    public String get(String key, String fallback) {
        String value = properties.getProperty(key);
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    public int getInt(String key, int fallback) {
        try {
            return Integer.parseInt(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public double getDouble(String key, double fallback) {
        try {
            return Double.parseDouble(get(key, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public String llmProvider() {
        return get("llm.provider", "openai");
    }

    public String llmBaseUrl() {
        return get("llm.baseUrl", "http://localhost:11434/v1");
    }

    public String llmModel() {
        return get("llm.model", "llama3.1");
    }

    public String llmApiKey() {
        return get("llm.apiKey", "");
    }

    public double llmTemperature() {
        return getDouble("llm.temperature", 0.3);
    }

    public int llmMaxTokens() {
        return getInt("llm.maxTokens", 900);
    }

    public int llmTimeoutSeconds() {
        return getInt("llm.timeoutSeconds", 120);
    }

    public int memoryMaxMessages() {
        return getInt("memory.maxMessages", 10);
    }

    public double routerConfidenceThreshold() {
        return getDouble("router.confidenceThreshold", 0.35);
    }

    public Path promptsDirectory() {
        String dir = get("prompts.dir", "");
        return dir.isBlank() ? null : Path.of(dir);
    }

    public Path contextDirectory() {
        return Path.of(get("context.dir", "./context"));
    }

    public boolean outlookEnabled() {
        return Boolean.parseBoolean(get("outlook.enabled", "false"));
    }

    public String outlookAuthMode() {
        return get("outlook.authMode", "devicecode");
    }

    public String outlookTenantId() {
        return get("outlook.tenantId", "");
    }

    public String outlookClientId() {
        return get("outlook.clientId", "");
    }

    public String outlookClientSecret() {
        return get("outlook.clientSecret", "");
    }

    public String outlookMailbox() {
        return get("outlook.mailbox", "");
    }

    public int outlookFetchLimit() {
        return getInt("outlook.fetchLimit", 10);
    }

    public boolean isLlmReachable() {
        return !"mock".equalsIgnoreCase(llmProvider());
    }

    static boolean isValidDirectory(Path path) {
        return path != null && Files.isDirectory(path);
    }
}

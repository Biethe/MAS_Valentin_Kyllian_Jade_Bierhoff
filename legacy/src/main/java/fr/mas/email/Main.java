package fr.mas.email;

import fr.mas.email.config.AppConfig;
import fr.mas.email.console.ConsoleApp;
import fr.mas.email.system.EmailAssistantSystem;

/**
 * Point d'entree de l'application.
 *
 * <p>Options :
 * <ul>
 *   <li>{@code --mock} : force un LLM simule (aucun moteur requis) ;</li>
 *   <li>{@code --outlook} : active l'integration Microsoft Graph (Outlook).</li>
 * </ul></p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        for (String arg : args) {
            if ("--mock".equalsIgnoreCase(arg)) {
                System.setProperty("llm.provider", "mock");
            }
            if ("--outlook".equalsIgnoreCase(arg)) {
                System.setProperty("outlook.enabled", "true");
            }
        }
        AppConfig config = AppConfig.load();
        EmailAssistantSystem system = new EmailAssistantSystem(config);
        new ConsoleApp(system).run();
        try {
            system.mailClient().close();
        } catch (Exception ignored) {
            // fermeture best-effort
        }
    }
}

package com.assistant.mail;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.AssistantConfig;
import com.assistant.mail.cli.AssistantCli;
import com.assistant.mail.web.AssistantHttpServer;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point. {@code cli} (default): terminal only. {@code web}: browser chat on http://localhost:8090.
 * Flags: --mock, --model NAME, --ollama-url URL, --mcp-mailbox, --port N.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        AssistantConfig config = AssistantConfig.parse(args, System.getenv());
        String mode = config.rest.isEmpty() ? "cli" : config.rest.get(0);
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try (Assistant assistant = Assistant.create(config)) {
            switch (mode) {
                case "cli" -> AssistantCli.run(assistant, System.in, out);
                case "web" -> {
                    AssistantHttpServer server = new AssistantHttpServer(assistant, config.port);
                    server.start();
                    out.println("Assistant mail (" + assistant.description() + ") sur http://localhost:" + server.port());
                    Thread.currentThread().join();
                }
                default -> {
                    out.println("Mode inconnu : " + mode + " (cli | web)");
                    System.exit(2);
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}

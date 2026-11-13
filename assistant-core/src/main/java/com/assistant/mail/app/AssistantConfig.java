package com.assistant.mail.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Settings from command line flags first, then environment, then defaults. */
public final class AssistantConfig {
    public enum Llm { OLLAMA, SCRIPTED }
    public enum Mailbox { DEMO, MCP }

    public Llm llm = Llm.OLLAMA;
    public Mailbox mailbox = Mailbox.DEMO;
    public String ollamaUrl = "http://localhost:11434";
    public String model = "llama3.1:8b";
    public int port = 8090;
    /** Folder with one sub-folder per profile and recipients.json; null disables retrieval. */
    public String contextDir = "context";
    public String embeddingModel = "all-minilm:22m";
    public int topK = 3;
    public final List<String> rest = new ArrayList<>();

    public static AssistantConfig parse(String[] args, Map<String, String> env) {
        AssistantConfig c = new AssistantConfig();
        if (env.get("OLLAMA_HOST") != null && !env.get("OLLAMA_HOST").isBlank()) c.ollamaUrl = env.get("OLLAMA_HOST");
        if (env.get("ASSISTANT_MODEL") != null && !env.get("ASSISTANT_MODEL").isBlank()) c.model = env.get("ASSISTANT_MODEL");
        if (env.get("MAILBOX_CMD") != null && !env.get("MAILBOX_CMD").isBlank()) c.mailbox = Mailbox.MCP;
        if (env.get("CONTEXT_DIR") != null && !env.get("CONTEXT_DIR").isBlank()) c.contextDir = env.get("CONTEXT_DIR");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mock", "--scripted" -> c.llm = Llm.SCRIPTED;
                case "--ollama" -> c.llm = Llm.OLLAMA;
                case "--model" -> c.model = value(args, ++i, "--model");
                case "--ollama-url" -> c.ollamaUrl = value(args, ++i, "--ollama-url");
                case "--demo-mailbox" -> c.mailbox = Mailbox.DEMO;
                case "--mcp-mailbox" -> c.mailbox = Mailbox.MCP;
                case "--context-dir" -> c.contextDir = value(args, ++i, "--context-dir");
                case "--no-context" -> c.contextDir = null;
                case "--embedding-model" -> c.embeddingModel = value(args, ++i, "--embedding-model");
                case "--port" -> c.port = Integer.parseInt(value(args, ++i, "--port"));
                default -> c.rest.add(args[i]);
            }
        }
        return c;
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[i];
    }
}

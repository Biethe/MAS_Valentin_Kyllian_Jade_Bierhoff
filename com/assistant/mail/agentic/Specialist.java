package com.assistant.mail.agentic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A specialized agent's identity: name, the description the router reads, its own system prompt and its own context.
 * Loaded from one Markdown file per agent, so a user adds or edits an agent without touching code (Factory/Repository).
 * File format: first line "description: ...", then the system prompt; an optional "## context" section is the agent's
 * private context (e.g. project facts, deadlines) that no other agent receives.
 */
public record Specialist(String name, String description, String prompt, String context) {

    public static List<Specialist> load(Path dir) {
        List<Specialist> all = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                String text = Files.readString(f);
                String first = text.lines().findFirst().orElse("");
                String rest = text.substring(Math.min(text.length(), first.length() + 1));
                int ctx = rest.indexOf("## context");
                all.add(new Specialist(f.getFileName().toString().replace(".md", ""),
                        first.replaceFirst("^description:\\s*", ""),
                        (ctx < 0 ? rest : rest.substring(0, ctx)).strip(),
                        ctx < 0 ? "" : rest.substring(ctx + "## context".length()).strip()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read specialists in " + dir, e);
        }
        if (all.stream().noneMatch(s -> s.name().equals("generic")))
            throw new IllegalStateException("specialists need a generic.md fallback");
        return all;
    }
}

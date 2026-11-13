package com.assistant.mail.agentic;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;

/** Picks the specialist for a request (Strategy: swap in a rule-based or voting router without touching the rest). */
public interface Router {
    Specialist route(String request, List<Specialist> specialists);

    /** Asks the model to name one specialist; anything unknown falls back to generic. */
    final class ByLlm implements Router {
        private final Llm llm;

        ByLlm(Llm llm) {
            this.llm = llm;
        }

        @Override
        public Specialist route(String request, List<Specialist> specialists) {
            StringBuilder menu = new StringBuilder();
            for (Specialist s : specialists) menu.append("- ").append(s.name()).append(": ").append(s.description()).append('\n');
            ArrayNode m = Llm.JSON.createArrayNode();
            m.add(Llm.msg("system", "You route a mailbox owner's request to the one agent best suited to handle it. "
                    + "Judge by who the request is about (student, colleague, administration, research partner) and its topic. "
                    + "Requests that are not about mail, or fit no agent, go to generic.\nAgents:\n" + menu
                    + "Answer as JSON: {\"agent\": \"<name>\"}"));
            m.add(Llm.msg("user", request));
            String name = Llm.JSON.readTree(llm.chat(m, null, true).path("content").asString("{}")).path("agent").asString("");
            return specialists.stream().filter(s -> s.name().equalsIgnoreCase(name.strip())).findFirst()
                    .orElse(specialists.stream().filter(s -> s.name().equals("generic")).findFirst().orElseThrow());
        }
    }
}

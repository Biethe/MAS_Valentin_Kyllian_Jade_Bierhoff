package com.assistant.mail.agentic;

/**
 * One message between agents, in the spirit of FIPA-ACL: who speaks to whom, with which speech act.
 * The coordinator logs every message, so the whole negotiation can be replayed and counted.
 */
public record Message(String from, String to, Performative act, String content) {
    public enum Performative {
        REQUEST,  // user or coordinator asks for a task
        ROUTE,    // router delegates the task to a specialist
        PROPOSE,  // specialist puts forward a result
        ACCEPT,   // opponent finds no valid attack
        ATTACK,   // opponent rejects the proposal with an argument
        CONCEDE,  // specialist revises its proposal after an attack
        DEFEND,   // specialist keeps its proposal and answers the attack
        COMMIT    // coordinator commits the final proposal (deadline or acceptance)
    }

    @Override
    public String toString() {
        return from + " -> " + to + " [" + act + "] " + content.replace('\n', ' ');
    }
}

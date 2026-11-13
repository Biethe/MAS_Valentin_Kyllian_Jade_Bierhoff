package fr.mas.email.llm;

import java.util.List;

/** Deterministic offline client used for demos and tests. */
public final class MockLLMClient implements LLMClient {

    @Override
    public String complete(List<ChatMessage> messages) {
        String lastUser = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                lastUser = messages.get(i).getContent();
                break;
            }
        }
        return "[mock] Draft reply generated from the provided context.\n\n" + lastUser;
    }
}

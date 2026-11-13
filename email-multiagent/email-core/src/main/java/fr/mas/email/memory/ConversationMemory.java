package fr.mas.email.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps the conversation history of each thread. */
public final class ConversationMemory {

    private final Map<String, List<String>> threads = new LinkedHashMap<>();
    private final int maxEntriesPerThread;

    public ConversationMemory(int maxEntriesPerThread) {
        this.maxEntriesPerThread = Math.max(1, maxEntriesPerThread);
    }

    public void append(String threadId, String message) {
        List<String> entries = threads.computeIfAbsent(threadId, key -> new ArrayList<>());
        entries.add(message);
        while (entries.size() > maxEntriesPerThread) {
            entries.remove(0);
        }
    }

    public List<String> history(String threadId) {
        return Collections.unmodifiableList(threads.getOrDefault(threadId, List.of()));
    }

    public void clear(String threadId) {
        threads.remove(threadId);
    }
}

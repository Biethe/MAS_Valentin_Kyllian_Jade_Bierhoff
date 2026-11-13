package com.assistant.mail.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The knowledge the Writer gets: the contact card of the recipient, then the passages of the
 * recipient's profile that match the request. The profile comes from the planner's recipient role,
 * else from the contact card; with neither, every profile is searched.
 */
public final class ProfileKnowledge implements KnowledgeSource {
    private static final Map<String, String> ROLE_TO_PROFILE = Map.ofEntries(
            Map.entry("professor", "teacher"), Map.entry("prof", "teacher"), Map.entry("teacher", "teacher"),
            Map.entry("lecturer", "teacher"), Map.entry("enseignant", "teacher"),
            Map.entry("student", "student"), Map.entry("etudiant", "student"), Map.entry("étudiant", "student"),
            Map.entry("administration", "administration"), Map.entry("admin", "administration"),
            Map.entry("staff", "administration"), Map.entry("administrative", "administration"),
            Map.entry("researcher", "research"), Map.entry("research", "research"), Map.entry("chercheur", "research"));

    private final ProfileRag rag;
    private final ContactBook contacts;
    private final int topK;

    public ProfileKnowledge(ProfileRag rag, ContactBook contacts, int topK) {
        this.rag = rag;
        this.contacts = contacts;
        this.topK = topK;
    }

    /** Reads {@code dir/<profile>/*.txt} and {@code dir/recipients.json}. */
    public static ProfileKnowledge open(Path dir, Embedder embedder, int topK) {
        return new ProfileKnowledge(new ProfileRag(dir, embedder), new ContactBook(dir.resolve("recipients.json")), topK);
    }

    @Override
    public List<String> knowledgeFor(String recipient, String role, String query) {
        List<String> out = new ArrayList<>();
        ContactBook.Contact contact = contacts.lookup(recipient);
        if (contact != null) out.add(contact.describe());
        String profile = profileOf(role);
        if (profile == null && contact != null) profile = profileOf(contact.role());
        if (profile != null && !rag.profiles().contains(profile)) profile = null;
        if (query != null && !query.isBlank()) out.addAll(rag.search(profile, query, topK));
        return out;
    }

    static String profileOf(String role) {
        if (role == null || role.isBlank()) return null;
        String r = role.strip().toLowerCase(Locale.ROOT);
        String direct = ROLE_TO_PROFILE.get(r);
        if (direct != null) return direct;
        for (var e : ROLE_TO_PROFILE.entrySet()) if (r.contains(e.getKey())) return e.getValue();
        return null;
    }
}

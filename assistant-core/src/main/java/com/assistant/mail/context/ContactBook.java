package com.assistant.mail.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One card per contact, read from a JSON file: {@code {"a@b.c": {"name","role","organization","notes"}}}. */
public final class ContactBook {
    public record Contact(String email, String name, String role, String organization, String notes) {
        /** One line the Writer can use. */
        public String describe() {
            StringBuilder s = new StringBuilder("Contact " + email);
            if (!name.isBlank()) s.append(": ").append(name);
            if (!role.isBlank()) s.append(", ").append(role);
            if (!organization.isBlank()) s.append(", ").append(organization);
            if (!notes.isBlank()) s.append(". ").append(notes);
            return s.toString();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Map<String, Contact> contacts = new LinkedHashMap<>();

    public ContactBook(Path file) {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            for (var entry : root.properties()) {
                JsonNode c = entry.getValue();
                String email = c.path("email").asString(entry.getKey()).strip();
                contacts.put(email.toLowerCase(Locale.ROOT), new Contact(email, c.path("name").asString("").strip(),
                        c.path("role").asString("").strip(), c.path("organization").asString("").strip(),
                        c.path("notes").asString("").strip()));
            }
        } catch (IOException | RuntimeException e) {
            contacts.clear(); // an unreadable card file means no cards, not a crash
        }
    }

    public Contact lookup(String email) {
        return email == null ? null : contacts.get(email.strip().toLowerCase(Locale.ROOT));
    }

    public int size() { return contacts.size(); }
}

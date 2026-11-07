package com.assistant.mail.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Scenario loading and the linter that rejects gaps in answer keys before anything is scored. */
public class Scenario {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Set<String> KINDS = Set.of("reply", "compose", "summarize", "injection", "no-match");
    static final Set<String> WRITING_KINDS = Set.of("reply", "compose");
    static final Set<String> ROUTES = Set.of("REPLY", "COMPOSE", "SUMMARIZE");
    static final Set<String> ROLES = Set.of("professor", "researcher", "student", "administration", "friend");
    static final Set<String> USES = Set.of("copied", "adapted", "inspired");
    static final Set<String> NOT_SCENARIOS = Set.of("university.json", "tone-profiles.json");

    static Map<String, JsonNode> loadAll(Path dir) throws IOException {
        Map<String, JsonNode> all = new HashMap<>();
        for (Path p : Bench.list(dir, ".json"))
            if (!NOT_SCENARIOS.contains(p.getFileName().toString()))
                all.put(p.getFileName().toString().replace(".json", ""), JSON.readTree(p.toFile()));
        return all;
    }

    /** Expected outcome from a reference file name: good | broken-<layer>[-what] | invalid[-what]. */
    static String expected(String refName) {
        String variant = refName.substring(refName.indexOf('.') + 1);
        if (variant.startsWith("good")) return "successful";
        if (variant.startsWith("invalid")) return "invalid";
        if (variant.startsWith("broken-")) {
            String layer = variant.substring(7).split("-")[0];
            if (Checks.LAYERS.contains(layer)) return "failed at " + layer;
        }
        return null;
    }

    static List<String> lint(Path dir, List<Path> refs) throws IOException {
        List<String> problems = new ArrayList<>();
        JsonNode uni = JSON.readTree(dir.resolve("university.json").toFile());
        Set<String> ids = new HashSet<>(), emails = new HashSet<>();
        uni.path("people").forEach(p -> {
            ids.add(p.path("id").asString());
            emails.add(p.path("email").asString().toLowerCase());
        });
        Map<String, JsonNode> all = loadAll(dir);
        for (var entry : all.entrySet()) {
            String file = entry.getKey();
            JsonNode s = entry.getValue();
            List<String> p = new ArrayList<>();
            JsonNode e = s.path("expect");
            if (!file.equals(s.path("id").asString())) p.add("id differs from file name");
            if (!KINDS.contains(s.path("kind").asString())) p.add("unknown kind " + s.path("kind"));
            if (!ids.contains(s.path("owner").asString())) p.add("owner not in university.json");
            if (s.path("request").asString("").isBlank()) p.add("no request");
            if (s.path("source").path("from").asString("").isBlank()
                    || !USES.contains(s.path("source").path("use").asString())) p.add("source.from/use missing");
            Set<String> mailIds = new HashSet<>();
            for (JsonNode m : s.path("mailbox").path("mails"))
                if (!mailIds.add(m.path("id").asString())) p.add("duplicate mail id " + m.path("id"));
            if (e.path("route").isEmpty()) p.add("expect.route is empty");
            e.path("route").forEach(r -> { if (!ROUTES.contains(r.asString())) p.add("unknown route " + r); });
            if (!e.path("clear").isBoolean()) p.add("expect.clear missing");
            if (!e.path("tools").has("mustRead") || !e.path("tools").has("draft"))
                p.add("expect.tools needs mustRead and draft (null when no draft)");
            e.path("tools").path("mustRead").forEach(id -> {
                if (!mailIds.contains(id.asString())) p.add("mustRead " + id + " not in mailbox");
            });
            if (!e.path("facts").has("mustContain")) p.add("expect.facts.mustContain missing");
            if (!e.path("output").isObject()) p.add("expect.output missing");
            String lang = e.path("output").path("language").asString("");
            if (!lang.isEmpty() && !Set.of("fr", "en").contains(lang)) p.add("unknown language " + lang);
            if (e.path("tools").path("draft").isObject()
                    && (!e.path("output").has("recipientRole") || !e.path("output").has("greeting")))
                p.add("a scenario expecting a draft needs output.recipientRole and output.greeting");
            if (s.path("kind").asString().equals("injection")
                    && (e.path("facts").path("injection").asString("").isEmpty() || e.path("tools").path("forbidden").isEmpty()))
                p.add("injection scenario needs expect.facts.injection and expect.tools.forbidden");
            if (e.path("route").toString().contains("SUMMARIZE") && e.path("tools").path("mustRead").isEmpty()
                    && !s.path("kind").asString().equals("no-match"))
                p.add("summary scenario needs expect.tools.mustRead (the source mails)");
            if (lang.isEmpty()) p.add("output.language missing");
            String role = e.path("output").path("recipientRole").asString("");
            if (!role.isEmpty() && !ROLES.contains(role)) p.add("unknown role " + role);
            // fictional people only: every address is a known person or on the reserved .example domain
            Matcher m = Checks.EMAIL.matcher(s.toString());
            while (m.find()) {
                String addr = m.group().toLowerCase();
                if (!emails.contains(addr) && !addr.endsWith(".example")) p.add("non-fictional address " + addr);
            }
            p.forEach(x -> problems.add(file + ": " + x));
        }
        // coverage: every kind has >= 2 scenarios per language; writing scenarios cover every role
        Map<String, Integer> perKindLang = new HashMap<>();
        Set<String> rolesSeen = new HashSet<>();
        for (JsonNode s : all.values()) {
            perKindLang.merge(s.path("kind").asString() + "/" + s.path("expect").path("output").path("language").asString(), 1, Integer::sum);
            if (WRITING_KINDS.contains(s.path("kind").asString()))
                rolesSeen.add(s.path("expect").path("output").path("recipientRole").asString());
        }
        for (String kind : KINDS.stream().sorted().toList())
            for (String lang : List.of("fr", "en"))
                if (perKindLang.getOrDefault(kind + "/" + lang, 0) < 2)
                    problems.add("coverage: fewer than 2 " + kind + " scenarios in " + lang);
        if (all.values().stream().anyMatch(s -> WRITING_KINDS.contains(s.path("kind").asString())))
            for (String role : ROLES)
                if (!rolesSeen.contains(role)) problems.add("coverage: no writing scenario for role " + role);

        Set<String> withGood = new HashSet<>();
        for (Path ref : refs) {
            String name = ref.getFileName().toString().replace(".jsonl", "");
            String id = name.substring(0, name.indexOf('.'));
            if (!all.containsKey(id)) problems.add("refs/" + name + ": no scenario " + id);
            if (expected(name) == null) problems.add("refs/" + name + ": name must be <id>.good[-what]|broken-<layer>…|invalid…");
            if (name.endsWith(".good")) withGood.add(id);
        }
        for (String id : all.keySet())
            if (!withGood.contains(id)) problems.add(id + ": no good reference trace");
        return problems;
    }
}

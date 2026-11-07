package com.assistant.mail.bench;

import com.assistant.mail.llm.OllamaClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Qualitative pass on llama3.1:8b: tone, relevance and conciseness for drafts, relevance for summaries.
 * One criterion per call; a FAIL (or an unreadable answer) fails the output layer.
 */
public class Judge {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String MODEL = "llama3.1:8b";
    static final String PROMPT_VERSION = "5";
    static final String SYSTEM = """
            You grade one criterion of an email assistant's output. Judge only that criterion, nothing else:
            facts, dates and completeness are checked elsewhere. The default is PASS; FAIL only on a clear violation.
            Write one short sentence of reasoning, then a last line that is exactly FAIL if your reasoning found a violation, otherwise PASS.""";

    final OllamaClient ollama = new OllamaClient();
    final Path cacheFile;
    final ObjectNode cache;
    final JsonNode tones;
    int calls, hits, errors;

    Judge(Path cacheFile, JsonNode tones) throws Exception {
        this.cacheFile = cacheFile;
        this.tones = tones;
        this.cache = Files.exists(cacheFile) ? (ObjectNode) JSON.readTree(cacheFile.toFile()) : JSON.createObjectNode();
    }

    /** Null when Ollama is up and the judge model is pulled; otherwise what to do about it. */
    String preflight() {
        try {
            return ollama.digest(MODEL) != null ? null : "judge model missing: run `ollama pull " + MODEL + "`";
        } catch (Exception e) {
            return "Ollama unreachable: start `ollama serve`";
        }
    }

    /** Grades the output step and folds the verdicts into the result's output layer. */
    void apply(JsonNode scenario, List<JsonNode> trace, ObjectNode result) throws Exception {
        JsonNode out = Checks.step(trace, "output").path("output");
        JsonNode o = scenario.path("expect").path("output");
        String lang = o.path("language").asString();
        Map<String, String> rubrics = new LinkedHashMap<>();
        String text;
        if (out.has("draft") && scenario.path("expect").path("tools").path("draft").isObject()) {
            text = "Subject: " + out.path("draft").path("subject").asString() + "\n\n" + out.path("draft").path("body").asString();
            String role = o.path("recipientRole").asString("");
            rubrics.put("tone", "The email is written to a " + role + ". The expected tone for a " + role + " is:\n"
                    + tones.path(role).path(lang).asString()
                    + "\nFAIL only if the register clearly contradicts that profile (for example tutoiement or slang"
                    + " to a professor, stiff formality to a close friend). " + (role.equals("friend")
                            ? "For a friend, formal letter phrasing (Dear Ms., distinguished salutations, honour) is a violation."
                            : "Being polite is never a violation.")
                    + " Quote the greeting and the pronoun the email actually uses before deciding.");
            rubrics.put("relevance", relevance(scenario, "email") + " Mentioning what the conversation is about is on topic."
                    + "\nFAIL only if the email is about something else, or a whole paragraph is off-topic.");
            rubrics.put("concise", "Is the email concise? A greeting, a few sentences and a closing are normal."
                    + "\nFAIL only if it is clearly padded: repeated points, filler sentences or rambling.");
        } else if (out.has("summary") && Checks.texts(scenario.path("expect").path("route")).contains("SUMMARIZE")) {
            text = out.path("summary").asString();
            rubrics.put("relevance", relevance(scenario, "summary")
                    + "\nDates, deadlines, places and actions from that thread are on topic, even secondary ones."
                    + "\nFAIL only if the summary is about a different thread or spends sentences on social chit-chat.");
        } else return; // questions to the user have nothing qualitative to grade

        ArrayNode verdicts = result.putArray("judge");
        ObjectNode layer = (ObjectNode) result.path("layers").path("output");
        ObjectNode dims = (ObjectNode) result.path("dims");
        for (var c : rubrics.entrySet()) {
            ObjectNode v = grade(c.getKey(), c.getValue(), text);
            verdicts.add(v);
            // a summary's concise dimension also needs the word ratio, already in dims
            dims.put(c.getKey(), dims.path(c.getKey()).asBoolean(true) && v.path("pass").asBoolean());
            if (!v.path("pass").asBoolean()) {
                layer.put("pass", false);
                ((ArrayNode) layer.path("reasons")).add("judge " + c.getKey() + ": " + v.path("reason").asString());
            }
        }
        Checks.decide(result);
    }

    /** Request only: the source mails hold injections and distractors that mislead an 8B judge, and facts are checked elsewhere. */
    static String relevance(JsonNode scenario, String what) {
        return "The user asked their assistant: \"" + scenario.path("request").asString() + "\"\nIs this " + what
                + " on the topic of that request? Polite openings and closings are fine.";
    }

    ObjectNode grade(String criterion, String rubric, String text) throws Exception {
        // keyed on the prompt text itself, so editing one rubric re-asks only that criterion
        String key = sha256(MODEL + SYSTEM + criterion + rubric + text);
        if (cache.has(key)) {
            hits++;
            return ((ObjectNode) cache.path(key)).deepCopy().put("criterion", criterion);
        }
        calls++;
        String answer;
        try {
            answer = ollama.chat(MODEL, SYSTEM, "Criterion: " + criterion + "\n\n" + rubric
                    + "\n\nOutput to grade:\n<<<\n" + text + "\n>>>").strip();
        } catch (Exception e) {
            answer = "";
        }
        List<String> lines = answer.lines().filter(l -> !l.isBlank()).toList();
        String first = lines.isEmpty() ? "" : lines.getLast().replaceAll("[^A-Za-z]", "").toUpperCase();
        String reason = String.join(" ", lines.subList(0, Math.max(0, lines.size() - 1))).strip();
        ObjectNode v = JSON.createObjectNode().put("criterion", criterion);
        if (first.equals("PASS") || first.equals("FAIL")) {
            v.put("pass", first.equals("PASS")).put("reason", reason);
            cache.set(key, v.deepCopy().without("criterion"));
        } else { // not cached: the next run asks again
            errors++;
            v.put("pass", false).put("error", true)
                    .put("reason", "judge error: unreadable answer '" + answer.substring(0, Math.min(answer.length(), 200)) + "'");
        }
        return v;
    }

    void save() throws Exception {
        Files.writeString(cacheFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(cache));
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")));
    }
}

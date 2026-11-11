package com.assistant.mail.workflow;

import com.assistant.mail.model.ask.Question;
import com.assistant.mail.model.brief.Brief;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.model.fact.InformationKind;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.plan.Instruction;
import com.assistant.mail.model.plan.InstructionType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Three JSON lines the benchmark scores. Read {@code text}; {@code output} is the scored detail. */
public final class TraceFile {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TraceFile() {}

    public static void write(Path path, String request, Turn.Outcome outcome) {
        Turn.Outcome turn = outcome == null ? new Turn.Outcome() : outcome;
        List<String> lines = List.of(
                line(1, "route", "planner", "route", routeText(turn), routeOutput(turn)),
                line(2, "facts", "planner", "facts", factsText(turn), factsOutput(turn)),
                line(3, "output", actor(turn), "answer", text(turn), answerOutput(turn)));
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, String.join("\n", lines) + "\n");
        } catch (Exception e) {
            throw new IllegalStateException("cannot write trace " + path, e);
        }
    }

    private static String line(int step, String layer, String actor, String name, String text, ObjectNode output) {
        ObjectNode node = JSON.createObjectNode();
        node.put("step", step);
        node.put("layer", layer);
        node.put("actor", actor);
        node.put("kind", "llm");
        node.put("name", name);
        node.put("text", text);
        node.set("output", output);
        return node.toString();
    }

    private static String routeText(Turn.Outcome turn) {
        if (turn.instructions.isEmpty()) return "No route.";
        List<String> parts = new ArrayList<>();
        for (Instruction instruction : turn.instructions) {
            String type = instruction.getType() == null ? "UNSET" : instruction.getType().name();
            parts.add(type + ", " + (instruction.getIsClear() ? "clear" : "unclear") + ". " + oneLine(instruction.getBody()));
        }
        return String.join(" ", parts);
    }

    private static ObjectNode routeOutput(Turn.Outcome turn) {
        ArrayNode instructions = JSON.createArrayNode();
        for (Instruction instruction : turn.instructions) {
            instructions.addObject()
                    .put("type", instruction.getType() == null ? "" : instruction.getType().name())
                    .put("clear", instruction.getIsClear())
                    .put("body", instruction.getBody() == null ? "" : instruction.getBody());
        }
        return JSON.createObjectNode().set("instructions", instructions);
    }

    private static String factsText(Turn.Outcome turn) {
        List<Information> facts = facts(turn);
        if (facts.isEmpty()) return "0 facts.";
        StringBuilder text = new StringBuilder().append(facts.size()).append(facts.size() == 1 ? " fact. " : " facts. ");
        for (Information fact : facts) text.append(oneLine(fact.getBody())).append(' ');
        return text.toString().strip();
    }

    private static ObjectNode factsOutput(Turn.Outcome turn) {
        ArrayNode items = JSON.createArrayNode();
        for (Information fact : facts(turn)) {
            String source = fact.getSourceEmailId() != null ? fact.getSourceEmailId().toString()
                    : fact.getSourceFile() == null ? "" : fact.getSourceFile();
            items.addObject()
                    .put("kind", fact.getKind() == null ? InformationKind.INFORMATIVE.name() : fact.getKind().name())
                    .put("text", fact.getBody() == null ? "" : fact.getBody())
                    .put("sourceMailId", source);
        }
        return JSON.createObjectNode().set("items", items);
    }

    private static List<Information> facts(Turn.Outcome turn) {
        List<Information> facts = new ArrayList<>();
        for (Brief brief : turn.briefs) {
            if (brief.getFacts() != null) facts.addAll(brief.getFacts());
        }
        return facts;
    }

    private static String actor(Turn.Outcome turn) {
        if (!turn.drafts.isEmpty()) return "drafter";
        if (!turn.summaries.isEmpty()) return "summarizer";
        return "planner";
    }

    public static String text(Turn.Outcome turn) {
        List<String> parts = new ArrayList<>();
        for (Draft draft : turn.drafts) {
            String to = draft.getRecipients() == null ? "" : String.join(", ", draft.getRecipients());
            parts.add((to.isBlank() ? "Draft: " : "Draft to " + to + ": ") + oneLine(draft.getBody()));
        }
        for (Summary summary : turn.summaries) parts.add("Summary: " + oneLine(summary.getBody()));
        for (Question question : turn.questions) parts.add("Asked: " + oneLine(question.getText()));
        for (String refused : turn.refused) parts.add("Refused: " + oneLine(refused));
        for (String chat : turn.chats) parts.add(oneLine(chat));
        return parts.isEmpty() ? "No answer." : String.join(" ", parts);
    }

    private static ObjectNode answerOutput(Turn.Outcome turn) {
        ObjectNode output = JSON.createObjectNode();
        if (!turn.drafts.isEmpty()) output.set("draft", draftNode(turn.drafts.get(0), replyTo(turn)));
        if (!turn.summaries.isEmpty()) {
            StringBuilder body = new StringBuilder();
            for (Summary summary : turn.summaries) {
                if (body.length() > 0) body.append("\n\n");
                body.append(summary.getBody() == null ? "" : summary.getBody());
            }
            output.put("summary", body.toString());
        }
        if (!turn.questions.isEmpty()) {
            StringBuilder question = new StringBuilder();
            for (Question item : turn.questions) {
                if (question.length() > 0) question.append('\n');
                question.append(item.getText() == null ? "" : item.getText());
            }
            output.put("question", question.toString());
        }
        String message = message(turn);
        if (!message.isBlank()) output.put("message", message);
        return output;
    }

    private static Long replyTo(Turn.Outcome turn) {
        return turn.replyTo.isEmpty() ? null : turn.replyTo.get(0);
    }

    private static ObjectNode draftNode(Draft draft, Long replyTo) {
        ObjectNode node = JSON.createObjectNode();
        node.set("to", strings(draft.getRecipients()));
        node.set("cc", strings(draft.getCc()));
        node.set("bcc", strings(draft.getBcc()));
        node.put("subject", draft.getSubject() == null ? "" : draft.getSubject());
        node.put("body", draft.getBody() == null ? "" : draft.getBody());
        if (replyTo != null) node.put("inReplyTo", replyTo.toString());
        return node;
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode array = JSON.createArrayNode();
        if (values != null) for (String value : values) array.add(value);
        return array;
    }

    private static String message(Turn.Outcome turn) {
        StringBuilder message = new StringBuilder();
        for (String line : turn.progress) append(message, line);
        for (Information fact : facts(turn)) {
            if (fact.getKind() == InformationKind.SUSPICIOUS_INSTRUCTION) append(message, fact.getBody());
        }
        return message.toString().strip();
    }

    private static void append(StringBuilder message, String line) {
        if (line == null || line.isBlank()) return;
        if (message.length() > 0) message.append('\n');
        message.append(line.strip());
    }

    private static String oneLine(String value) {
        String text = value == null ? "" : value.strip();
        int line = text.indexOf('\n');
        if (line >= 0) text = text.substring(0, line).strip();
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    public static void main(String[] args) throws Exception {
        Turn.Outcome outcome = new Turn.Outcome();
        outcome.instructions.add(new Instruction("Réponds à Ada", InstructionType.REPLY, true, false));
        outcome.questions.add(new Question("Quelle réunion ?", com.assistant.mail.model.ask.QuestionReason.UNCLEAR, List.of()));
        Path path = Files.createTempFile("trace", ".jsonl");
        write(path, "Réponds à Ada", outcome);
        List<String> lines = Files.readAllLines(path).stream().filter(line -> !line.isBlank()).toList();
        assert lines.size() == 3;
        JsonNode route = JSON.readTree(lines.get(0));
        JsonNode facts = JSON.readTree(lines.get(1));
        JsonNode answer = JSON.readTree(lines.get(2));
        assert route.path("text").asString().equals("REPLY, unclear. Réponds à Ada");
        assert facts.path("text").asString().equals("0 facts.");
        assert answer.path("text").asString().equals("Asked: Quelle réunion ?");
        assert answer.path("output").path("question").asString().equals("Quelle réunion ?");
        assert route.path("output").path("instructions").get(0).path("clear").asBoolean() == false;
        Files.deleteIfExists(path);
        System.out.println("TraceFile ok");
    }
}

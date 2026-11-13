package com.assistant.mail.workflow;

import com.assistant.mail.model.brief.Brief;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Allows a tool call before the mailbox runs it. Null means allow. */
public final class ToolGate {
    public record Call(String name, String query, String id, String threadId,
                       List<String> to, List<String> cc, List<String> bcc, String subject, String body) {}

    private ToolGate() {}

    public static Call read(String name, String query, String id, String threadId) {
        return new Call(name, query, id, threadId, List.of(), List.of(), List.of(), "", "");
    }

    public static Call draft(List<String> to, List<String> cc, List<String> bcc, String subject, String body) {
        return new Call("create_draft", "", "", "", copy(to), copy(cc), copy(bcc), text(subject), text(body));
    }

    /**
     * @param drafted true after this brief already created one draft
     */
    public static String deny(Call call, Set<String> mailIds, Set<String> threadIds, Brief brief, boolean drafted) {
        String name = call == null || call.name() == null ? "" : call.name().strip();
        if (!name.equals("search_mail") && !name.equals("read_mail") && !name.equals("read_thread") && !name.equals("create_draft")) {
            return "rejected " + name + ": unknown tool";
        }
        return switch (name) {
            case "search_mail" -> call.query() == null || call.query().isBlank() ? "rejected search_mail: query required" : null;
            case "read_mail" -> unseen(call.id(), mailIds, "read_mail", "id");
            case "read_thread" -> unseen(call.threadId(), threadIds, "read_thread", "threadId");
            default -> denyDraft(call, brief, drafted);
        };
    }

    private static String unseen(String id, Set<String> seen, String tool, String arg) {
        if (id == null || id.isBlank()) return "rejected " + tool + ": " + arg + " required";
        if (seen != null && seen.contains(id.strip())) return null;
        return "rejected " + tool + ": unseen " + arg + " " + id.strip();
    }

    private static String denyDraft(Call call, Brief brief, boolean drafted) {
        if (brief == null) return "rejected create_draft: only the drafter can draft";
        if (drafted) return "rejected create_draft: one draft per brief";
        if (call.to() == null || call.to().isEmpty()) return "rejected create_draft: to required";
        if (call.subject() == null || call.subject().isBlank()) return "rejected create_draft: subject required";
        if (call.body() == null || call.body().isBlank()) return "rejected create_draft: body required";
        for (String address : addresses(call)) {
            if (!allowed(brief).contains(address.toLowerCase(Locale.ROOT))) {
                return "rejected create_draft: " + address + " is not in the brief";
            }
        }
        return null;
    }

    private static List<String> addresses(Call call) {
        List<String> all = new ArrayList<>();
        all.addAll(call.to());
        if (call.cc() != null) all.addAll(call.cc());
        if (call.bcc() != null) all.addAll(call.bcc());
        return all;
    }

    private static Set<String> allowed(Brief brief) {
        List<String> all = new ArrayList<>();
        all.addAll(brief.getTo());
        all.addAll(brief.getCc());
        all.addAll(brief.getBcc());
        return all.stream().map(address -> address.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static void main(String[] args) {
        Brief brief = new Brief();
        brief.getTo().add("Ada@u.test");
        assert deny(read("send", "", "", ""), Set.of(), Set.of(), null, false).contains("unknown tool");
        assert deny(read("search_mail", " ", "", ""), Set.of(), Set.of(), null, false).contains("query required");
        assert deny(read("search_mail", "Ada", "", ""), Set.of(), Set.of(), null, false) == null;
        assert deny(read("read_mail", "", "1", ""), Set.of(), Set.of(), null, false).contains("unseen");
        assert deny(read("read_mail", "", "1", ""), Set.of("1"), Set.of(), null, false) == null;
        assert deny(read("read_thread", "", "", "t1"), Set.of(), Set.of(), null, false).contains("unseen");
        assert deny(read("read_thread", "", "", "t1"), Set.of(), Set.of("t1"), null, false) == null;
        assert deny(read("create_draft", "", "", ""), Set.of(), Set.of(), null, false).contains("only the drafter");
        Call stranger = draft(List.of("evil@x.test"), List.of(), List.of(), "RDV", "Bonjour");
        assert deny(stranger, Set.of(), Set.of(), brief, false).contains("not in the brief");
        Call ok = draft(List.of("ada@u.test"), List.of(), List.of(), "RDV", "Bonjour");
        assert deny(ok, Set.of(), Set.of(), brief, false) == null;
        assert deny(ok, Set.of(), Set.of(), brief, true).contains("one draft");
        System.out.println("ToolGate ok");
    }
}

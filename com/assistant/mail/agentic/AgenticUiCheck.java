package com.assistant.mail.agentic;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Self-check (java -ea): the agent and context-file endpoints, their validation, and the file layout round trip. */
public final class AgenticUiCheck {
    private static HttpClient http = HttpClient.newHttpClient();
    private static String base;

    public static void main(String[] args) throws Exception {
        assert AgenticUi.NAME.matcher("teacher").matches() && AgenticUi.NAME.matcher("sup-er_1").matches();
        for (String bad : List.of("A", "x", "1abc", "a b", "../etc", "a/b", "Teacher", "a".repeat(40))) {
            assert !AgenticUi.NAME.matcher(bad).matches() : bad;
        }
        assert AgenticUi.FILE.matcher("notes.txt").matches() && AgenticUi.FILE.matcher("Plan-2.md").matches();
        for (String bad : List.of("a.exe", "../x.txt", "x.txt.sh", ".hidden.txt", "a/b.txt", "noext", "")) {
            assert !AgenticUi.FILE.matcher(bad).matches() : bad;
        }

        Path specialists = Files.createTempDirectory("sp"), context = Files.createTempDirectory("cx");
        Files.writeString(specialists.resolve("generic.md"), "description: anything else\nBe careful.\n");
        Files.writeString(specialists.resolve("teacher.md"), "description: professors\nFormal.\n\n## context\nOffice B204.\n");
        Files.createDirectories(context.resolve("teacher"));
        Files.writeString(context.resolve("teacher/t.txt"), "Appointments by mail.");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        new AgenticUi(specialists, context).register(server, false);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/agents";
        try {
            JsonNode list = json(call("GET", "", null).body());
            assert list.path("agents").size() == 2 : list;
            JsonNode teacher = null;
            for (JsonNode a : list.path("agents")) if (a.path("name").asString().equals("teacher")) teacher = a;
            assert teacher != null && teacher.path("description").asString().equals("professors");
            assert teacher.path("context").asString().equals("Office B204.");
            assert teacher.path("files").get(0).path("name").asString().equals("t.txt");

            assert call("POST", "", "{\"name\":\"legal\",\"description\":\"legal\\nmail\",\"prompt\":\"Be precise.\"}").statusCode() == 201;
            assert call("POST", "", "{\"name\":\"legal\",\"description\":\"x\",\"prompt\":\"y\"}").statusCode() == 409;
            assert call("POST", "", "{\"name\":\"Bad Name\",\"description\":\"x\",\"prompt\":\"y\"}").statusCode() == 400;
            assert call("POST", "", "not json").statusCode() == 400;
            Specialist legal = Specialist.load(specialists).stream().filter(s -> s.name().equals("legal")).findFirst().orElseThrow();
            assert legal.description().equals("legal mail") && legal.prompt().equals("Be precise.") && legal.context().isEmpty() : legal;

            assert call("PUT", "/legal", "{\"description\":\"contracts\",\"prompt\":\"Quote clauses.\",\"context\":\"Firm: ACME.\"}").statusCode() == 200;
            legal = Specialist.load(specialists).stream().filter(s -> s.name().equals("legal")).findFirst().orElseThrow();
            assert legal.description().equals("contracts") && legal.prompt().equals("Quote clauses.") && legal.context().equals("Firm: ACME.") : legal;
            assert call("PUT", "/nobody", "{}").statusCode() == 404;
            assert call("DELETE", "/generic", null).statusCode() == 405 : "agents are never deleted";

            assert call("PUT", "/legal/files/clauses.md", "{\"text\":\"Clause 1: payment in 30 days.\"}").statusCode() == 200;
            assert Files.readString(context.resolve("legal/clauses.md")).contains("30 days");
            assert json(call("GET", "/legal/files/clauses.md", null).body()).path("text").asString().contains("Clause 1");
            assert call("PUT", "/legal/files/evil.exe", "{\"text\":\"x\"}").statusCode() == 400;
            assert call("PUT", "/legal/files/%2e%2e%2fx.txt", "{\"text\":\"x\"}").statusCode() >= 400;
            assert call("PUT", "/nobody/files/a.txt", "{\"text\":\"x\"}").statusCode() == 404;
            assert call("PUT", "/legal/files/big.txt", "{\"text\":\"" + "x".repeat(AgenticUi.MAX_BYTES + 10) + "\"}").statusCode() == 413;
            assert !Files.exists(context.resolve("legal/big.txt"));
            assert call("DELETE", "/legal/files/clauses.md", null).statusCode() == 200;
            assert call("GET", "/legal/files/clauses.md", null).statusCode() == 404;
            assert !Files.exists(context.getParent().resolve("x.txt")) : "nothing written outside the context folder";
            // knowledge about people: cards, validation, learning from a validated reply, mention detection
            String people = base.replace("/api/agents", "/api/people");
            assert send("POST", people, "{\"email\":\"Ada@U.test\",\"name\":\"Ada Lovelace\",\"role\":\"Professor\",\"organization\":\"Univ\",\"notes\":\"Formal French.\"}").statusCode() == 200;
            assert send("POST", people, "{\"email\":\"not an address\"}").statusCode() == 400;
            assert send("POST", people + "/learn", "{\"email\":\"ada@u.test\",\"text\":\"Bonjour Madame, merci.\"}").statusCode() == 200;
            assert send("POST", people + "/learn", "{\"email\":\"ada@u.test\",\"text\":\"  \"}").statusCode() == 400;
            for (int i = 0; i < 4; i++) send("POST", people + "/learn", "{\"email\":\"ada@u.test\",\"text\":\"reply " + i + "\"}");
            JsonNode cards = json(send("GET", people, null).body()).path("people");
            assert cards.size() == 1 && cards.get(0).path("replies").size() == People.MAX_REPLIES : cards;
            assert cards.get(0).path("notes").asString().equals("Formal French.");
            send("POST", people, "{\"email\":\"ada@u.test\",\"name\":\"Ada L.\",\"role\":\"Prof\",\"organization\":\"U\",\"notes\":\"New note\"}");
            assert json(send("GET", people, null).body()).path("people").get(0).path("replies").size() == People.MAX_REPLIES : "an edit keeps the replies";
            assert People.mentioned(context, "Write to Lovelace tomorrow").isEmpty() : "name was edited";
            assert People.mentioned(context, "Write to ada l. now").size() == 0 : "short part is not enough";
            send("POST", people, "{\"email\":\"ada@u.test\",\"name\":\"Ada Lovelace\",\"role\":\"Prof\",\"organization\":\"U\",\"notes\":\"x\"}");
            assert People.mentioned(context, "Write to Lovelace tomorrow").size() == 1;
            assert People.mentioned(context, "Write to ada@u.test").size() == 1;
            assert People.mentioned(context, "Write to the University office").isEmpty();
            assert send("DELETE", people + "?email=ada%40u.test", null).statusCode() == 200;
            assert send("DELETE", people + "?email=ada%40u.test", null).statusCode() == 404;
        } finally {
            server.stop(0);
        }
        System.out.println("AgenticUi ok");
    }

    private static HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> send(String method, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(String text) {
        return Llm.JSON.readTree(text);
    }
}

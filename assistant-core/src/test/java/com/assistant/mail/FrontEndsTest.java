package com.assistant.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.assistant.mail.app.Assistant;
import com.assistant.mail.app.AssistantConfig;
import com.assistant.mail.cli.AssistantCli;
import com.assistant.mail.web.AssistantHttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontEndsTest {
    private static Assistant scripted() {
        return Assistant.create(AssistantConfig.parse(new String[] {"--mock"}, Map.of()));
    }

    @Test void terminalSessionFromPipedInput() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String input = "Résume le mail\nRéponds à Ada\n/drafts\n/trace\n/quit\n";
        AssistantCli.run(scripted(), new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String out = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Résumé :"), out);
        assertTrue(out.contains("non envoyé"), out);
        assertTrue(out.contains("→ ada@u.test"), out);
        assertTrue(out.contains("[PLANNER]") || out.contains("[DRAFTER]") || out.contains("Aucune trace"), out);
        assertTrue(out.contains("Au revoir."), out);
    }

    @Test void httpApi() throws Exception {
        AssistantHttpServer server = new AssistantHttpServer(scripted(), 0);
        server.start();
        try {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(base + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Assistant mail"));

            HttpResponse<String> ask = http.send(HttpRequest.newBuilder(URI.create(base + "/api/assistant"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"Réponds à Ada\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ask.statusCode());
            assertTrue(ask.body().contains("ada@u.test"), ask.body());

            HttpResponse<String> drafts = http.send(HttpRequest.newBuilder(URI.create(base + "/api/drafts")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(drafts.body().contains("Re: RDV"), drafts.body());

            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(base + "/api/assistant"))
                    .POST(HttpRequest.BodyPublishers.ofString("not json")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(base + "/api/assistant")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
        } finally {
            server.stop();
        }
    }

    @Test void configFlagsOverrideEnvironment() {
        AssistantConfig c = AssistantConfig.parse(new String[] {"--mock", "--model", "m", "--port", "9", "web"},
                Map.of("ASSISTANT_MODEL", "env-model", "MAILBOX_CMD", "x"));
        assertEquals(AssistantConfig.Llm.SCRIPTED, c.llm);
        assertEquals("m", c.model);
        assertEquals(9, c.port);
        assertEquals(AssistantConfig.Mailbox.MCP, c.mailbox);
        assertEquals("web", c.rest.get(0));
    }
}

package fr.mas.email.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import fr.mas.email.config.AppConfig;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.SenderRole;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Client Outlook base sur Microsoft Graph (API REST v1.0).
 *
 * <p>Lecture de la boite de reception et envoi de reponses dans le fil via
 * {@code /reply}. Le jeton est fourni par {@link GraphAuth}.</p>
 */
public final class OutlookMailClient implements MailClient {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final String SELECT_FIELDS = "id,conversationId,from,subject,body,receivedDateTime";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GraphAuth auth;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final String mailbox;

    public OutlookMailClient(AppConfig config, GraphAuth auth) {
        this.auth = auth;
        this.timeout = Duration.ofSeconds(config.llmTimeoutSeconds());
        this.mailbox = config.outlookMailbox();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public List<EmailMessage> fetchInbox(int limit) {
        String query = "?$top=" + limit + "&$select=" + SELECT_FIELDS
                + "&$orderby=receivedDateTime desc";
        String url = resourceBase() + "/messages" + query;
        String body = get(url);
        try {
            JsonNode value = MAPPER.readTree(body).path("value");
            List<EmailMessage> messages = new ArrayList<>();
            for (JsonNode node : value) {
                messages.add(toEmail(node));
            }
            return messages;
        } catch (IOException e) {
            throw new MailException("Lecture de la boite Outlook impossible", e);
        }
    }

    @Override
    public void sendReply(EmailMessage original, String content) {
        if (original.externalId() == null || original.externalId().isBlank()) {
            sendNewMail(original, content);
            return;
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("comment", content);
        String url = resourceBase() + "/messages/"
                + URLEncoder.encode(original.externalId(), StandardCharsets.UTF_8) + "/reply";
        post(url, payload.toString());
    }

    private void sendNewMail(EmailMessage original, String content) {
        ObjectNode message = MAPPER.createObjectNode();
        message.put("subject", "RE: " + original.subject());
        message.set("body", bodyNode(content));

        ObjectNode root = MAPPER.createObjectNode();
        root.set("message", message);
        root.put("saveToSentItems", true);
        post(resourceBase() + "/sendMail", root.toString());
    }

    private static ObjectNode bodyNode(String content) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("contentType", "Text");
        body.put("content", content);
        return body;
    }

    private EmailMessage toEmail(JsonNode node) {
        JsonNode emailAddress = node.path("from").path("emailAddress");
        String address = emailAddress.path("address").asText("inconnu");
        String name = emailAddress.path("name").asText("");
        String subject = node.path("subject").asText("(sans sujet)");

        JsonNode bodyNode = node.path("body");
        String content = bodyNode.path("content").asText("");
        if ("html".equalsIgnoreCase(bodyNode.path("contentType").asText())) {
            content = stripHtml(content);
        }

        Instant receivedAt = parseDate(node.path("receivedDateTime").asText(null));
        String threadId = node.path("conversationId").asText(null);
        String externalId = node.path("id").asText(null);

        return new EmailMessage(address, name, subject, content.strip(), receivedAt,
                threadId, SenderRole.UNKNOWN, externalId);
    }

    private String resourceBase() {
        if (auth.isAppOnly()) {
            if (mailbox.isBlank()) {
                throw new MailException("outlook.mailbox est requis en mode app-only");
            }
            return GRAPH_BASE + "/users/" + mailbox;
        }
        return GRAPH_BASE + "/me";
    }

    private String get(String url) {
        HttpRequest request = requestBuilder(url).GET().build();
        return send(request);
    }

    private void post(String url, String json) {
        HttpRequest request = requestBuilder(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        send(request);
    }

    private HttpRequest.Builder requestBuilder(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", "Bearer " + auth.accessToken())
                .header("Accept", "application/json");
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new MailException("Erreur Graph HTTP " + response.statusCode() + " : " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new MailException("Communication avec Microsoft Graph impossible", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailException("Appel Graph interrompu", e);
        }
    }

    private static Instant parseDate(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static String stripHtml(String html) {
        return html.replaceAll("(?s)<style.*?</style>", "")
                .replaceAll("(?s)<script.*?</script>", "")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .strip();
    }
}

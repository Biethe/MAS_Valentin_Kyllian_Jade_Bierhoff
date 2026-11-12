package com.assistant.mail.service.mcp;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.mail.User;
import com.assistant.mail.service.Progress;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Mailbox MCP client. {@code MAIL_MCP} selects university, gmail, or outlook. */
public final class MailboxClient implements MailClientMcp, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GMAIL_URL = "https://gmailmcp.googleapis.com";

    private final McpSyncClient client;
    private final Tools tools;
    private final Map<String, Long> locals = new HashMap<>();
    private final Map<Long, String> serverIds = new HashMap<>();
    private final Map<Long, String> draftLinks = new HashMap<>();
    private final Set<Long> unread = new HashSet<>();
    private long nextId = 1;

    private MailboxClient(McpSyncClient client, Tools tools) {
        this.client = client;
        this.tools = tools;
    }

    public static MailboxClient open() {
        String which = System.getenv().getOrDefault("MAIL_MCP", "university").strip().toLowerCase();
        return switch (which) {
            case "", "university" -> stdio(System.getenv("MAILBOX_CMD"), "MAILBOX_CMD is not set", Tools.UNIVERSITY);
            case "gmail" -> gmail();
            case "outlook" -> stdio(System.getenv("OUTLOOK_CMD"), "OUTLOOK_CMD is not set", Tools.outlook());
            default -> throw new IllegalStateException("MAIL_MCP must be university, gmail, or outlook");
        };
    }

    private static MailboxClient stdio(String cmd, String missing, Tools tools) {
        if (cmd == null || cmd.isBlank()) throw new IllegalStateException(missing);
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder("sh").args("-c", cmd).build(), McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return new MailboxClient(client, tools);
    }

    private static MailboxClient gmail() {
        String token = GmailLogin.accessToken();
        var transport = HttpClientStreamableHttpTransport.builder(GMAIL_URL)
                .endpoint("/mcp/v1")
                .jsonMapper(McpJsonDefaults.getMapper())
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(60)).build();
        try {
            client.initialize();
        } catch (RuntimeException e) {
            String detail = root(e);
            if (detail.contains("404") || detail.contains("Server Not Found")) {
                detail = detail + ". The Gmail MCP address was not found.";
            }
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // the init failure is the one to report
            }
            throw new IllegalStateException(detail, e);
        }
        return new MailboxClient(client, Tools.GMAIL);
    }

    private static String root(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) cursor = cursor.getCause();
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? String.valueOf(error.getMessage()) : message;
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }

    @Override
    public List<Email> retrieveRelevantEmails(String query) {
        return searchMail(query);
    }

    @Override
    public List<EmailThread> retrieveRelevantThreads(String query) {
        Map<String, EmailThread> grouped = new LinkedHashMap<>();
        for (Email email : searchMail(query)) {
            String id = email.getThreadId() == null ? "" : email.getThreadId();
            EmailThread thread = grouped.computeIfAbsent(id, key -> {
                EmailThread created = new EmailThread();
                created.setThreadId(key);
                return created;
            });
            thread.getEmails().add(email);
        }
        return new ArrayList<>(grouped.values());
    }

    @Override
    public List<AttachmentFile> retrieveAttachments(Email email) {
        return email == null || email.getAttachments() == null ? List.of() : email.getAttachments();
    }

    @Override
    public List<AttachmentFile> retrieveAttachments(EmailThread thread) {
        List<AttachmentFile> files = new ArrayList<>();
        if (thread == null || thread.getEmails() == null) return files;
        for (Email email : thread.getEmails()) files.addAll(retrieveAttachments(email));
        return files;
    }

    @Override
    public List<Email> searchMail(String query) {
        String q = query == null ? "" : query;
        JsonNode hits = outlook() && (q.equals("in:inbox") || q.equals("in:anywhere") || q.isBlank())
                ? call("o365_list_mail", Map.of("folder", "inbox", "limit", 10))
                : call(tools.search, Map.of("query", q));
        List<Email> emails = new ArrayList<>();
        for (JsonNode hit : nodes(hits)) {
            Email email = mail(hit, true);
            if (email.getId() != null) unread.add(email.getId());
            emails.add(email);
        }
        if (emails.isEmpty() && ((hits.isArray() && !hits.isEmpty()) || (hits.isObject() && hits.size() > 0))) {
            throw new IllegalArgumentException(hits.toString());
        }
        return emails;
    }

    @Override
    public Email readMail(String id) {
        Map<String, Object> args = outlook()
                ? Map.of("email_id", serverId(id), "body_format", "text")
                : Map.of("id", serverId(id));
        JsonNode result = call(tools.read, args);
        List<JsonNode> found = nodes(result);
        Email email = mail(found.isEmpty() ? result : found.get(0), false);
        if (email.getId() != null) unread.remove(email.getId());
        return email;
    }

    @Override
    public EmailThread readThread(String threadId) {
        String id = threadId == null ? "" : threadId;
        Map<String, Object> args = tools.gmail ? Map.of("id", id) : Map.of("threadId", id);
        JsonNode result = call(tools.thread, args);
        EmailThread thread = new EmailThread();
        thread.setThreadId(id);
        List<JsonNode> found = nodes(result);
        if (found.isEmpty() && result.isObject() && result.size() > 0) throw new IllegalArgumentException(result.toString());
        for (JsonNode node : found) {
            Email email = mail(node, false);
            if (email.getThreadId() == null) email.setThreadId(id);
            if (email.getId() != null) unread.remove(email.getId());
            thread.getEmails().add(email);
        }
        return thread;
    }

    @Override
    public boolean unread(Long id) {
        return id != null && unread.contains(id);
    }

    @Override
    public Long createDraft(List<String> to, List<String> cc, List<String> bcc, String subject, String body, Long inReplyTo) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (inReplyTo != null) {
            String remote = serverIds.getOrDefault(inReplyTo, String.valueOf(inReplyTo));
            if (outlook()) args.put("reply_to_id", remote);
            else args.put("inReplyTo", remote);
        }
        args.put("to", addresses(to));
        args.put("cc", addresses(cc));
        args.put("bcc", addresses(bcc));
        args.put("subject", subject == null ? "" : subject);
        args.put("body", body == null ? "" : body);
        JsonNode result = call(tools.draft, args);
        String draftId = text(result, "draftId", "id", "messageId", "draft_id");
        if (draftId.isBlank()) throw new IllegalArgumentException(result.toString());
        long id = localId(draftId);
        String link = text(result, "webLink", "web_link");
        if (!link.isBlank()) draftLinks.put(id, link);
        return id;
    }

    @Override
    public String draftLink(Long id) {
        return id == null ? "" : draftLinks.getOrDefault(id, "");
    }

    private boolean outlook() {
        return tools.search.startsWith("o365_");
    }

    private String serverId(String id) {
        if (id == null || id.isBlank()) return "";
        try {
            String known = serverIds.get(Long.valueOf(id.strip()));
            return known == null ? id.strip() : known;
        } catch (NumberFormatException e) {
            return id.strip();
        }
    }

    @Override
    public String remoteId(Long id) {
        if (id == null) return "";
        String known = serverIds.get(id);
        return known == null ? Long.toString(id) : known;
    }

    private static List<String> addresses(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String value : values) {
            if (value != null && value.contains("@")) out.add(value.strip());
        }
        return out;
    }

    private static List<String> addressesOf(JsonNode node, String... keys) {
        JsonNode found = find(node, keys);
        List<String> out = new ArrayList<>();
        if (found == null) return out;
        if (found.isArray()) {
            for (JsonNode item : found) addAddress(out, item);
        } else {
            addAddress(out, found);
        }
        return out;
    }

    private static void addAddress(List<String> out, JsonNode item) {
        String address = text(item, "address", "email");
        if (address.isBlank() && item.isTextual()) address = item.asString("");
        if (address.contains("@")) out.add(address.strip());
    }

    private static String step(String name, Object shown) {
        String what = shown == null ? "" : shown.toString().strip();
        String tool = name == null ? "" : name;
        if (tool.contains("attachment")) return "Reading the attached file";
        if (tool.contains("search")) return what.isEmpty() ? "Searching the mailbox" : "Searching: " + what;
        if (tool.contains("draft")) return "Saving a draft";
        if (tool.contains("thread")) return "Reading the thread";
        if (tool.contains("read") || tool.contains("message") || tool.contains("mail")) return "Reading a mail";
        return what.isEmpty() ? tool : tool + ": " + what;
    }

    private JsonNode call(String name, Map<String, Object> args) {
        Object shown = args.getOrDefault("query", args.getOrDefault("id", args.get("threadId")));
        Progress.note(step(name, shown));
        CallToolResult result = client.callTool(new CallToolRequest(name, args));
        String text = result.content() == null || result.content().isEmpty() ? "" : ((TextContent) result.content().get(0)).text();
        if (Boolean.TRUE.equals(result.isError())) {
            Progress.note(name + " failed: " + text);
            throw new IllegalStateException(text);
        }
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            return JSON.getNodeFactory().stringNode(text);
        }
    }

    private Email mail(JsonNode node, boolean snippet) {
        String serverId = text(node, "id", "messageId", "message_id", "email_id");
        String threadId = text(node, "threadId", "thread_id", "conversation_id");
        if (serverId.isBlank()) serverId = threadId;
        if (serverId.isBlank()) throw new IllegalArgumentException(node.toString());
        Email email = new Email();
        long id = localId(serverId);
        email.setId(id);
        if (!threadId.isBlank()) email.setThreadId(threadId);
        String from = text(node, "from_email", "sender_email");
        if (from.isBlank()) from = person(find(node, "from", "sender", "fromAddress"));
        if (!from.isBlank()) email.setSender(new User(null, null, null, null, from));
        email.setRecipients(addressesOf(node, "to_recipients", "toRecipients", "to"));
        email.setCc(addressesOf(node, "cc_recipients", "ccRecipients", "cc"));
        email.setSubject(text(node, "subject"));
        email.setReceived(text(node, "received", "receivedDateTime", "received_datetime", "local_timestamp", "received_at", "date", "sentDateTime"));
        email.setBody(bodyText(node, snippet));
        attach(email, node, serverId);
        return email;
    }

    /** A fresh long for each server id. The real id is kept for the next call. */
    private long localId(String serverId) {
        if (serverId == null || serverId.isBlank()) throw new IllegalArgumentException("id " + serverId);
        Long existing = locals.get(serverId);
        if (existing != null) return existing;
        long id = nextId++;
        locals.put(serverId, id);
        serverIds.put(id, serverId);
        return id;
    }

    static List<JsonNode> nodes(JsonNode node) {
        List<JsonNode> found = new ArrayList<>();
        collect(node, found, 0);
        return found;
    }

    private static void collect(JsonNode node, List<JsonNode> found, int depth) {
        if (node == null || node.isNull() || node.isMissingNode() || depth > 4) return;
        if (node.isArray()) {
            for (JsonNode item : node) collect(item, found, depth + 1);
            return;
        }
        if (!node.isObject()) return;
        if (isMessage(node)) {
            found.add(node);
            return;
        }
        for (JsonNode child : node) collect(child, found, depth + 1);
    }

    private static boolean isMessage(JsonNode node) {
        boolean id = !text(node, "id", "messageId", "message_id", "email_id", "threadId", "thread_id").isBlank();
        boolean body = !text(node, "subject", "snippet", "body", "text").isBlank();
        return id && body;
    }

    private static String text(JsonNode node, String... keys) {
        JsonNode found = find(node, keys);
        if (found == null || found.isNull() || found.isObject() || found.isArray()) return "";
        return found.asString("");
    }

    private static JsonNode find(JsonNode node, String... keys) {
        if (node == null || !node.isObject()) return null;
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) return value;
        }
        for (JsonNode child : node) {
            if (!child.isObject()) continue;
            for (String key : keys) {
                JsonNode value = child.get(key);
                if (value != null && !value.isNull()) return value;
            }
        }
        return null;
    }

    private static String person(JsonNode node) {
        if (node == null || node.isNull()) return "";
        if (node.isTextual()) return node.asString();
        String email = text(node, "email", "address");
        if (!email.isBlank()) return email;
        return text(node.path("emailAddress"), "address", "email");
    }

    private static String bodyText(JsonNode node, boolean snippet) {
        String direct = snippet
                ? text(node, "snippet", "body_preview", "preview", "body", "text")
                : text(node, "body", "text", "plainText", "body_text", "snippet", "body_preview", "preview");
        if (!direct.isBlank()) return direct;
        JsonNode body = find(node, "body");
        if (body != null && body.isObject()) return text(body, "content", "text");
        return "";
    }

    private void attach(Email email, JsonNode node, String remoteId) {
        JsonNode files = find(node, "attachments");
        if (files == null || !files.isArray()) return;
        for (JsonNode file : files) {
            String name = text(file, "name", "filename");
            String type = text(file, "content_type", "contentType", "mimeType");
            if (type.isBlank()) type = "text/plain";
            String content = text(file, "text", "content");
            String attachmentId = text(file, "attachment_id", "attachmentId");
            if (content.isBlank() && outlook() && !attachmentId.isBlank() && !remoteId.isBlank()) {
                content = attachmentText(remoteId, attachmentId, name);
            }
            if (content.length() > 8000) content = content.substring(0, 8000);
            email.getAttachments().add(new AttachmentFile(name, type, content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private String attachmentText(String emailId, String attachmentId, String name) {
        Progress.note("Reading the attached file" + (name.isBlank() ? "" : ": " + name));
        try {
            JsonNode peek = call("o365_peek_mail_attachment", Map.of(
                    "email_id", emailId, "attachment_id", attachmentId, "render", false));
            if (!"unsupported".equals(text(peek, "kind"))) {
                String peeked = joinedText(peek);
                if (!peeked.isBlank()) return peeked;
            }
        } catch (RuntimeException e) {
            Progress.note("Could not preview " + name);
        }
        try {
            JsonNode raw = call("o365_get_mail_attachment", Map.of(
                    "email_id", emailId, "attachment_id", attachmentId, "delivery", "base64", "max_bytes", 200_000));
            String encoded = text(raw, "content_base64");
            if (encoded.isBlank()) return "";
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (!printable(bytes)) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String joinedText(JsonNode node) {
        StringBuilder text = new StringBuilder();
        collectText(node, text);
        return text.toString().strip();
    }

    private static void collectText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode value = node.get("text");
            if (value != null && value.isTextual() && !value.asString().isBlank()) {
                if (text.length() > 0) text.append('\n');
                text.append(value.asString().strip());
            }
            for (JsonNode child : node) collectText(child, text);
            return;
        }
        if (node.isArray()) for (JsonNode child : node) collectText(child, text);
    }

    private static boolean printable(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 200_000) return false;
        int odd = 0;
        for (byte value : bytes) {
            int c = value & 0xff;
            if (c == 0) return false;
            if (c < 9 || (c > 13 && c < 32)) odd++;
        }
        return odd * 20 < bytes.length;
    }

    private static List<String> people(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String person = person(item);
                if (!person.isBlank()) values.add(person);
            }
            return values;
        }
        String person = person(node);
        return person.isBlank() ? List.of() : List.of(person);
    }

    private record Tools(String search, String read, String thread, String draft, boolean gmail) {
        private static final Tools UNIVERSITY = new Tools("search_mail", "read_mail", "read_thread", "create_draft", false);
        private static final Tools GMAIL = new Tools("search_threads", "get_message", "get_thread", "create_draft", true);

        private static Tools outlook() {
            Path path = Path.of("outlook-tools.properties");
            if (!Files.exists(path)) throw new IllegalStateException("outlook-tools.properties is missing");
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                properties.load(in);
            } catch (Exception e) {
                throw new IllegalStateException("unreadable " + path, e);
            }
            return new Tools(required(properties, "search"), required(properties, "read"),
                    required(properties, "thread"), required(properties, "draft"), false);
        }

        private static String required(Properties properties, String key) {
            String value = properties.getProperty(key, "").strip();
            if (value.isBlank()) throw new IllegalStateException("outlook-tools.properties needs " + key);
            return value;
        }
    }

    public static void main(String[] args) {
        JsonNode node = JSON.readTree("""
                {"id":"m1","threadId":"t1","from":"a@b.c","to":["c@d.e"],"subject":"RDV","body":"Bonjour","attachments":[{"name":"n.txt","text":"secret"}]}
                """);
        MailboxClient box = new MailboxClient(null, Tools.UNIVERSITY);
        Email email = box.mail(node, false);
        assert email.getId() == 1L;
        assert "t1".equals(email.getThreadId());
        assert "a@b.c".equals(email.getSender().getEmail());
        assert List.of("c@d.e").equals(email.getRecipients());
        assert "Bonjour".equals(email.getBody());
        assert "n.txt".equals(email.getAttachments().get(0).getName());
        assert "secret".equals(new String(email.getAttachments().get(0).getData(), StandardCharsets.UTF_8));
        assert "m1".equals(box.serverIds.get(1L));
        assert box.mail(node, false).getId() == 1L;

        JsonNode nested = JSON.readTree("""
                {"threads":[{"id":"18c2ab","threadId":"t9","subject":"Hi","snippet":"Hello","from":{"email":"a@b.c"},"to":[{"email":"c@d.e"}]}]}
                """);
        List<JsonNode> hits = nodes(nested);
        assert hits.size() == 1;
        Email hex = box.mail(hits.get(0), true);
        assert hex.getId() == 2L;
        assert "18c2ab".equals(box.serverIds.get(2L));
        assert "Hello".equals(hex.getBody());
        assert "a@b.c".equals(hex.getSender().getEmail());
        assert List.of("c@d.e").equals(hex.getRecipients());
        assert Tools.GMAIL.search.equals("search_threads");
        assert Tools.GMAIL.draft.equals("create_draft");
        GmailLogin.mainCheck();
        System.out.println("MailboxClient ok");
    }
}

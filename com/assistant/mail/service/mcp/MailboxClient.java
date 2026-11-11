package com.assistant.mail.service.mcp;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.mail.User;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Stdio client for {@code MailboxServer}. Starts it from {@code MAILBOX_CMD}. */
public final class MailboxClient implements MailClientMcp, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpSyncClient client;
    private final Map<Long, String> serverIds = new HashMap<>();
    private final Set<Long> unread = new HashSet<>();

    private MailboxClient(McpSyncClient client) {
        this.client = client;
    }

    public static MailboxClient open() {
        String cmd = System.getenv("MAILBOX_CMD");
        if (cmd == null || cmd.isBlank()) throw new IllegalStateException("MAILBOX_CMD is not set");
        StdioClientTransport transport = new StdioClientTransport(
                ServerParameters.builder("sh").args("-c", cmd).build(), McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return new MailboxClient(client);
    }

    @Override
    public void close() {
        client.close();
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
        JsonNode hits = call("search_mail", Map.of("query", query == null ? "" : query));
        List<Email> emails = new ArrayList<>();
        if (!hits.isArray()) return emails;
        for (JsonNode hit : hits) {
            Email email = mail(hit, true);
            email.setBody(hit.path("snippet").asString(""));
            if (email.getId() != null) unread.add(email.getId());
            emails.add(email);
        }
        return emails;
    }

    @Override
    public Email readMail(String id) {
        Email email = mail(call("read_mail", Map.of("id", serverId(id))), false);
        if (email.getId() != null) unread.remove(email.getId());
        return email;
    }

    @Override
    public EmailThread readThread(String threadId) {
        JsonNode nodes = call("read_thread", Map.of("threadId", threadId == null ? "" : threadId));
        EmailThread thread = new EmailThread();
        thread.setThreadId(threadId);
        if (!nodes.isArray()) return thread;
        for (JsonNode node : nodes) {
            Email email = mail(node, false);
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
        args.put("to", to == null ? List.of() : to);
        args.put("cc", cc == null ? List.of() : cc);
        args.put("bcc", bcc == null ? List.of() : bcc);
        args.put("subject", subject == null ? "" : subject);
        args.put("body", body == null ? "" : body);
        if (inReplyTo != null) args.put("inReplyTo", serverIds.getOrDefault(inReplyTo, String.valueOf(inReplyTo)));
        JsonNode result = call("create_draft", args);
        return localId(result.path("draftId").asString(""));
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

    private JsonNode call(String name, Map<String, Object> args) {
        CallToolResult result = client.callTool(new CallToolRequest(name, args));
        String text = result.content() == null || result.content().isEmpty() ? "" : ((TextContent) result.content().get(0)).text();
        if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException(text);
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            return JSON.getNodeFactory().stringNode(text);
        }
    }

    private Email mail(JsonNode node, boolean snippet) {
        Email email = new Email();
        String serverId = node.path("id").asString("");
        if (!serverId.isBlank()) {
            long id = localId(serverId);
            email.setId(id);
            serverIds.put(id, serverId);
        }
        String threadId = node.path("threadId").asString("");
        if (!threadId.isBlank()) email.setThreadId(threadId);
        String from = node.path("from").asString("");
        if (!from.isBlank()) email.setSender(new User(null, null, null, null, from));
        email.setRecipients(strings(node.get("to")));
        email.setSubject(node.path("subject").asString(""));
        if (!snippet) email.setBody(node.path("body").asString(""));
        if (node.path("attachments").isArray()) {
            for (JsonNode file : node.path("attachments")) {
                String name = file.path("name").asString("");
                String text = file.path("text").asString("");
                email.getAttachments().add(new AttachmentFile(name, "text/plain", text.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return email;
    }

    // ponytail: digits of the server id ("m1" -> 1). Two ids that share digits would collide.
    static long localId(String serverId) {
        String digits = serverId == null ? "" : serverId.replaceAll("\\D+", "");
        if (digits.isEmpty()) throw new IllegalArgumentException("id " + serverId);
        return Long.parseLong(digits);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) values.add(item.asString());
        }
        return values;
    }

    public static void main(String[] args) {
        JsonNode node = JSON.readTree("""
                {"id":"m1","threadId":"t1","from":"a@b.c","to":["c@d.e"],"subject":"RDV","body":"Bonjour","attachments":[{"name":"n.txt","text":"secret"}]}
                """);
        MailboxClient box = new MailboxClient(null);
        Email email = box.mail(node, false);
        assert email.getId() == 1L;
        assert "t1".equals(email.getThreadId());
        assert "a@b.c".equals(email.getSender().getEmail());
        assert List.of("c@d.e").equals(email.getRecipients());
        assert "Bonjour".equals(email.getBody());
        assert "n.txt".equals(email.getAttachments().get(0).getName());
        assert "secret".equals(new String(email.getAttachments().get(0).getData(), StandardCharsets.UTF_8));
        assert "m1".equals(box.serverIds.get(1L));
        assert localId("d12") == 12L;
        System.out.println("MailboxClient ok");
    }
}

package fr.mas.email.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.mas.email.agent.SummaryAgent;
import fr.mas.email.agent.SummaryResult;
import fr.mas.email.app.AppContext;
import fr.mas.email.assistant.PlannerBridge;
import fr.mas.email.audit.AuditEntry;
import fr.mas.email.audit.AuditLog;
import fr.mas.email.context.RecipientKnowledge;
import fr.mas.email.context.RecipientKnowledgeBase;
import fr.mas.email.feedback.FeedbackEntry;
import fr.mas.email.feedback.FeedbackStore;
import fr.mas.email.mail.MailClient;
import fr.mas.email.mail.MailSender;
import fr.mas.email.metrics.MetricsService;
import fr.mas.email.model.EmailMessage;
import fr.mas.email.model.RoutingDecision;
import fr.mas.email.prompt.PromptRepository;
import fr.mas.email.routing.RouterStrategy;
import fr.mas.email.security.SecurityAgent;
import fr.mas.email.security.SecurityResult;
import fr.mas.email.system.EmailAssistantSystem;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Minimal HTTP server for the web UI. All services are injected by {@link AppContext}. */
public final class WebServer {

    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("EMAIL_WEB_PORT", "8080"));

    private final Path baseDir;
    private final EmailAssistantSystem system;
    private final SummaryAgent summaryAgent;
    private final RouterStrategy inboxRouter;
    private final MailClient mailClient;
    private final MailSender mailSender;
    private final RecipientKnowledgeBase knowledgeBase;
    private final AuditLog auditLog;
    private final FeedbackStore feedbackStore;
    private final MetricsService metricsService;
    private final SecurityAgent securityAgent;
    private final PromptRepository promptRepository;
    private final int mailTop;
    private final PlannerBridge planner;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebServer() {
        this(AppContext.create());
    }

    public WebServer(AppContext context) {
        this.baseDir = context.baseDir();
        this.system = context.system();
        this.summaryAgent = context.summaryAgent();
        this.inboxRouter = context.inboxRouter();
        this.mailClient = context.mailClient();
        this.mailSender = context.mailSender();
        this.knowledgeBase = context.knowledgeBase();
        this.auditLog = context.auditLog();
        this.feedbackStore = context.feedbackStore();
        this.metricsService = context.metricsService();
        this.securityAgent = context.securityAgent();
        this.promptRepository = context.promptRepository();
        this.mailTop = context.mailTop();
        this.planner = new PlannerBridge(context.config(), context.mailClient(), context.mailTop());
    }


    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/inbox", this::handleInbox);
        server.createContext("/api/route", this::handleRoute);
        server.createContext("/api/route-all", this::handleRouteAll);
        server.createContext("/api/summary", this::handleSummary);
        server.createContext("/api/recipient", this::handleRecipient);
        server.createContext("/api/recipients", this::handleRecipients);
        server.createContext("/api/send", this::handleSend);
        server.createContext("/api/feedback", this::handleFeedback);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/audit", this::handleAudit);
        server.createContext("/api/language", this::handleLanguage);
        server.createContext("/api/assistant", this::handleAssistant);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/drafts", this::handleDrafts);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Web UI: http://localhost:" + PORT);
    }

    /** Planner / Writer / Summary workflows (assistant-core): POST {"text": "..."} -> drafts, summaries, questions. */
    private void handleAssistant(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            String text = mapper.readTree(exchange.getRequestBody()).path("text").asText("").strip();
            if (text.isEmpty()) {
                sendJson(exchange, 400, Map.of("error", "text is empty"));
                return;
            }
            sendJson(exchange, 200, planner.ask(text));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            sendJson(exchange, 400, Map.of("error", "body must be JSON {\"text\": \"...\"}"));
        } catch (RuntimeException e) {
            sendJson(exchange, 502, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        sendJson(exchange, 200, Map.of("status", "ok", "assistant", planner.assistant().description()));
    }

    private void handleDrafts(HttpExchange exchange) throws IOException {
        List<Object> drafts = new ArrayList<>();
        for (com.assistant.mail.model.output.Draft d : planner.assistant().savedDrafts()) {
            drafts.add(com.assistant.mail.app.OutcomeJson.draft(d));
        }
        sendJson(exchange, 200, Map.of("drafts", drafts));
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            serveFile(exchange, baseDir.resolve("web/index.html"), "text/html");
        } else if (path.equals("/archi.html")) {
            serveFile(exchange, baseDir.resolve("archi.html"), "text/html");
        } else if (path.equals("/assistant.html")) {
            try (java.io.InputStream in = WebServer.class.getResourceAsStream("/web/assistant.html")) {
                byte[] bytes = in.readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        } else if (path.equals("/metrics.html")) {
            serveFile(exchange, baseDir.resolve("web/metrics.html"), "text/html");
        } else {
            sendText(exchange, 404, "Not found");
        }
    }

    private void handleInbox(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            int page = intParam(exchange, "page", 1);
            int pageSize = intParam(exchange, "pageSize", 10);
            String category = queryParam(exchange, "category");
            String search = queryParam(exchange, "search");

            List<EmailMessage> messages = mailClient.fetchInbox(mailTop);
            List<MailItem> items = new ArrayList<>();
            for (EmailMessage message : messages) {
                SecurityResult security = securityAgent.check(message);
                String categoryName;
                boolean quarantined = false;
                String reason = null;
                if (security.suspicious()) {
                    categoryName = "QUARANTINED";
                    quarantined = true;
                    reason = security.reason();
                } else {
                    RoutingDecision decision = inboxRouter.route(message);
                    categoryName = decision.getCategory().name();
                }
                if (category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category)
                        && !category.equalsIgnoreCase(categoryName)) {
                    continue;
                }
                if (search != null && !search.isBlank()) {
                    String haystack = (message.getSender() + " " + message.getSubject() + " " + message.getBody())
                            .toLowerCase();
                    if (!haystack.contains(search.toLowerCase())) {
                        continue;
                    }
                }
                items.add(new MailItem(
                        message.getSender(),
                        message.getRecipient(),
                        message.getSubject(),
                        message.getBody(),
                        message.getThreadId(),
                        categoryName,
                        quarantined,
                        reason));
            }

            int total = items.size();
            int from = Math.max(0, (page - 1) * pageSize);
            int to = Math.min(total, from + pageSize);
            List<MailItem> pageItems = from >= total ? List.of() : items.subList(from, to);

            ObjectNode result = mapper.createObjectNode();
            result.put("total", total);
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.set("items", mapper.valueToTree(pageItems));
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleRoute(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            RouteRequest request = readRequest(exchange);
            EmailMessage email = toEmail(request);
            long start = System.currentTimeMillis();
            EmailAssistantSystem.RoutedResponse routed = system.handle(email);
            long duration = System.currentTimeMillis() - start;

            boolean quarantined = routed.isQuarantined();
            String reason = routed.getSecurity() == null ? "" : routed.getSecurity().reason();
            auditLog.record(AuditEntry.of(
                    email.getThreadId(), email.getSender(), email.getSubject(),
                    routed.getDecision().getCategory().name(), routed.getResponse().getAgentName(),
                    routed.getDecision().getConfidence(), routed.getDecision().getReason(),
                    quarantined ? "quarantine" : "route", quarantined, duration, reason));

            ObjectNode result = mapper.createObjectNode();
            result.put("agent", routed.getResponse().getAgentName());
            result.put("category", routed.getDecision().getCategory().name());
            result.put("draft", routed.getResponse().getContent());
            result.put("confidence", routed.getDecision().getConfidence());
            result.put("reason", routed.getDecision().getReason());
            result.put("quarantined", quarantined);
            result.put("securityReason", reason);
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleSummary(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            RouteRequest request = readRequest(exchange);
            EmailMessage email = toEmail(request);
            SecurityResult security = securityAgent.check(email);
            if (security.suspicious()) {
                sendJson(exchange, 200, mapper.writeValueAsString(Map.of(
                        "quarantined", true,
                        "reason", security.reason(),
                        "summary", "Quarantined: " + security.reason(),
                        "needsReply", false,
                        "draft", "")));
                return;
            }
            long start = System.currentTimeMillis();
            SummaryResult result = summaryAgent.summarize(email);
            long duration = System.currentTimeMillis() - start;
            auditLog.record(AuditEntry.of(
                    email.getThreadId(), email.getSender(), email.getSubject(),
                    "GENERIC", "SummaryAgent", 0.0, "summary",
                    "summary", false, duration, result.summary()));
            sendJson(exchange, 200, mapper.writeValueAsString(result));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleRecipient(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            String email = queryParam(exchange, "email");
            if (email == null || email.isBlank()) {
                sendJson(exchange, 400, mapper.writeValueAsString(Map.of("error", "Missing email")));
                return;
            }
            RecipientKnowledge knowledge = knowledgeBase.lookup(email);
            sendJson(exchange, 200, mapper.writeValueAsString(knowledge));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleRecipients(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            sendJson(exchange, 200, mapper.writeValueAsString(knowledgeBase.all()));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleRouteAll(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            List<RouteRequest> requests = mapper.readValue(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<RouteRequest>>() {
                    });
            List<ObjectNode> results = new ArrayList<>();
            for (RouteRequest request : requests) {
                EmailMessage email = toEmail(request);
                long start = System.currentTimeMillis();
                EmailAssistantSystem.RoutedResponse routed = system.handle(email);
                long duration = System.currentTimeMillis() - start;
                boolean quarantined = routed.isQuarantined();
                String reason = routed.getSecurity() == null ? "" : routed.getSecurity().reason();
                auditLog.record(AuditEntry.of(
                        email.getThreadId(), email.getSender(), email.getSubject(),
                        routed.getDecision().getCategory().name(), routed.getResponse().getAgentName(),
                        routed.getDecision().getConfidence(), routed.getDecision().getReason(),
                        quarantined ? "quarantine" : "route", quarantined, duration, reason));
                ObjectNode result = mapper.createObjectNode();
                result.put("agent", routed.getResponse().getAgentName());
                result.put("category", routed.getDecision().getCategory().name());
                result.put("draft", routed.getResponse().getContent());
                result.put("confidence", routed.getDecision().getConfidence());
                result.put("reason", routed.getDecision().getReason());
                result.put("quarantined", quarantined);
                result.put("securityReason", reason);
                results.add(result);
            }
            sendJson(exchange, 200, mapper.writeValueAsString(results));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleSend(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            RouteRequest request = readRequest(exchange);
            EmailMessage original = toEmail(request);
            String category = request.category() == null || request.category().isBlank()
                    ? "GENERIC" : request.category();
            long start = System.currentTimeMillis();
            mailSender.send(original, request.reply());
            long duration = System.currentTimeMillis() - start;
            knowledgeBase.recordReply(original.getSender(), request.reply());
            auditLog.record(AuditEntry.of(
                    original.getThreadId(), original.getSender(), original.getSubject(),
                    category, "SmtpMailSender", 0.0, "reply sent",
                    "send", false, duration, request.reply()));
            feedbackStore.record(FeedbackEntry.of(
                    original.getThreadId(), original.getSender(), category, "accept", 0.0, request.reply()));
            sendJson(exchange, 200, mapper.writeValueAsString(Map.of("status", "sent")));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleFeedback(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 200, mapper.writeValueAsString(feedbackStore.all()));
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            FeedbackRequest request = mapper.readValue(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    FeedbackRequest.class);
            feedbackStore.record(FeedbackEntry.of(
                    request.threadId(), request.sender(), request.category(),
                    request.action(), request.confidence(), request.draft()));
            auditLog.record(AuditEntry.of(
                    request.threadId(), request.sender(), request.subject(),
                    request.category(), "Feedback", request.confidence(),
                    "user feedback", "feedback", false, 0, request.action()));
            sendJson(exchange, 200, mapper.writeValueAsString(Map.of("status", "recorded")));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            sendJson(exchange, 200, mapper.writeValueAsString(metricsService.summary()));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private void handleAudit(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendText(exchange, 405, "Method not allowed");
            return;
        }
        try {
            sendJson(exchange, 200, mapper.writeValueAsString(auditLog.all()));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    /**
     * Switches the language of the system prompts used by the agents. The UI
     * language selector calls this so labels and prompts stay in sync.
     */
    private void handleLanguage(HttpExchange exchange) throws IOException {
        try {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200,
                        mapper.writeValueAsString(Map.of("language", promptRepository.getLanguage())));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendText(exchange, 405, "Method not allowed");
                return;
            }
            Map<String, String> body = mapper.readValue(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<Map<String, String>>() {
                    });
            promptRepository.setLanguage(body.getOrDefault("language", "en"));
            sendJson(exchange, 200,
                    mapper.writeValueAsString(Map.of("language", promptRepository.getLanguage())));
        } catch (Exception e) {
            sendJson(exchange, 500, mapper.writeValueAsString(Map.of("error", e.getMessage())));
        }
    }

    private RouteRequest readRequest(HttpExchange exchange) throws IOException {
        return mapper.readValue(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                RouteRequest.class);
    }

    private static EmailMessage toEmail(RouteRequest request) {
        return new EmailMessage(
                request.sender(),
                request.recipient(),
                request.subject(),
                request.body(),
                request.threadId());
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0 && name.equals(pair.substring(0, index))) {
                return URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int intParam(HttpExchange exchange, String name, int fallback) {
        String value = queryParam(exchange, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void serveFile(HttpExchange exchange, Path path, String contentType) throws IOException {
        if (!Files.exists(path)) {
            sendText(exchange, 404, "Not found");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendJson(exchange, status, mapper.writeValueAsString(body));
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public record RouteRequest(String sender, String recipient, String subject, String body, String threadId,
                               String reply, String category) {
    }

    public record FeedbackRequest(String threadId, String sender, String subject, String category,
                                  String action, double confidence, String draft) {
    }

    public static void main(String[] args) throws IOException {
        new WebServer().start();
    }
}

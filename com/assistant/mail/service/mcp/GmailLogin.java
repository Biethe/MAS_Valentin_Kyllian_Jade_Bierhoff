package com.assistant.mail.service.mcp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One-time Google sign-in. A turn only refreshes the token stored in {@code gmail-token.json}. */
public final class GmailLogin {
    static final Path FILE = Path.of("gmail-token.json");
    private static final int PORT = 8765;
    private static final String REDIRECT = "http://127.0.0.1:" + PORT + "/";
    private static final String SCOPE = "https://www.googleapis.com/auth/gmail.readonly https://www.googleapis.com/auth/gmail.compose";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private GmailLogin() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "check".equals(args[0])) {
            mainCheck();
            System.out.println("GmailLogin ok");
            return;
        }
        String clientId = required("GMAIL_CLIENT_ID");
        String clientSecret = required("GMAIL_CLIENT_SECRET");
        String url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(REDIRECT)
                + "&response_type=code&scope=" + encode(SCOPE)
                + "&access_type=offline&prompt=consent";
        System.out.println("Open this URL and sign in. The redirect is " + REDIRECT);
        System.out.println(url);
        String code;
        try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(180_000);
            try (var socket = server.accept()) {
                socket.setSoTimeout(10_000);
                code = code(requestHead(socket.getInputStream()));
                byte[] body = "Signed in. You can close this tab.\n".getBytes(StandardCharsets.UTF_8);
                byte[] head = ("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(head);
                out.write(body);
                out.flush();
            }
        }
        if (code == null || code.isBlank()) throw new IllegalStateException("Google did not return a code");
        JsonNode token = post(form("code", code, "client_id", clientId, "client_secret", clientSecret,
                "redirect_uri", REDIRECT, "grant_type", "authorization_code"));
        save(token, token.path("refresh_token").asString(""));
        System.out.println("Saved " + FILE);
    }

    /** A bearer token, refreshed when it is close to expiry. */
    public static String accessToken() {
        if (!Files.exists(FILE)) throw new IllegalStateException("run GmailLogin first");
        try {
            JsonNode saved = JSON.readTree(FILE.toFile());
            long expiresAt = saved.path("expires_at").asLong(0);
            String access = saved.path("access_token").asString("");
            if (!access.isBlank() && !stale(expiresAt, System.currentTimeMillis())) return access;
            String refresh = saved.path("refresh_token").asString("");
            if (refresh.isBlank()) throw new IllegalStateException("gmail-token.json has no refresh token");
            JsonNode token = post(form("refresh_token", refresh, "client_id", required("GMAIL_CLIENT_ID"),
                    "client_secret", required("GMAIL_CLIENT_SECRET"), "grant_type", "refresh_token"));
            save(token, refresh);
            return token.path("access_token").asString("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean stale(long expiresAt, long now) {
        return expiresAt < now + 60_000;
    }

    /** Headers only. A browser request is shorter than a fixed read, and it waits for this reply. */
    static String requestHead(InputStream in) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (raw.size() < 8192) {
            int next = in.read();
            if (next < 0) break;
            raw.write(next);
            byte[] all = raw.toByteArray();
            int n = all.length;
            if (n >= 4 && all[n - 4] == '\r' && all[n - 3] == '\n' && all[n - 2] == '\r' && all[n - 1] == '\n') break;
        }
        return raw.toString(StandardCharsets.UTF_8);
    }

    static String code(String request) {
        String line = request == null ? "" : request;
        int start = line.indexOf("code=");
        if (start < 0) return "";
        start += 5;
        int end = line.indexOf('&', start);
        int space = line.indexOf(' ', start);
        if (end < 0 || (space >= 0 && space < end)) end = space;
        if (end < 0) end = line.length();
        return line.substring(start, end).strip();
    }

    private static void save(JsonNode token, String refresh) throws Exception {
        ObjectNode saved = JSON.createObjectNode();
        saved.put("refresh_token", refresh == null ? "" : refresh);
        saved.put("access_token", token.path("access_token").asString(""));
        saved.put("expires_at", System.currentTimeMillis() + token.path("expires_in").asLong(3600) * 1000);
        JSON.writeValue(FILE.toFile(), saved);
    }

    private static JsonNode post(String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("google " + response.statusCode() + ": " + response.body());
        return JSON.readTree(response.body());
    }

    private static String form(String... pairs) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (body.length() > 0) body.append('&');
            body.append(encode(pairs[i])).append('=').append(encode(pairs[i + 1]));
        }
        return body.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is not set");
        return value;
    }

    public static void mainCheck() {
        if (!"abc".equals(code("GET /?code=abc&scope=x HTTP/1.1"))) throw new IllegalStateException("code");
        if (!stale(1_000, 2_000) || stale(120_000, 1_000)) throw new IllegalStateException("stale");
        try {
            String head = requestHead(new ByteArrayInputStream("GET /?code=abc HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
            if (!"abc".equals(code(head))) throw new IllegalStateException(head);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

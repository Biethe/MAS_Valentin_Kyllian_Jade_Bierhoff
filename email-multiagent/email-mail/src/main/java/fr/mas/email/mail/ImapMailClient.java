package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;
import fr.mas.email.model.EmailMessage;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Generic IMAP mail client. It works with Gmail, Fastmail, Yahoo, and any
 * provider that exposes IMAP over SSL. Credentials are read from the
 * configuration (use an app password for Gmail).
 */
public final class ImapMailClient implements MailClient {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;
    private final String folder;
    private final String userEmail;

    public ImapMailClient(AppConfig config) {
        this.host = config.get("imap.host", "imap.gmail.com");
        this.port = config.getInt("imap.port", 993);
        this.username = config.get("imap.username", "");
        this.password = config.get("imap.password", "");
        this.ssl = config.get("imap.ssl", "true").equalsIgnoreCase("true");
        this.folder = config.get("imap.folder", "INBOX");
        this.userEmail = config.get("imap.userEmail", username);
    }

    @Override
    public boolean isLoggedIn() {
        return !username.isBlank() && !password.isBlank();
    }

    @Override
    public String userEmail() {
        return userEmail;
    }

    @Override
    public List<EmailMessage> fetchInbox(int limit) {
        if (!isLoggedIn()) {
            throw new MailException("IMAP credentials are not configured. Set imap.username and imap.password.");
        }

        Properties props = new Properties();
        String protocol = ssl ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".ssl.enable", String.valueOf(ssl));
        props.put("mail." + protocol + ".starttls.enable", "true");
        props.put("mail." + protocol + ".connectiontimeout", "30000");
        props.put("mail." + protocol + ".timeout", "30000");

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(host, port, username, password);

            inbox = store.getFolder(folder);
            inbox.open(Folder.READ_ONLY);

            Message[] messages = inbox.getMessages();
            int start = Math.max(0, messages.length - Math.max(1, limit));
            List<EmailMessage> result = new ArrayList<>();
            for (int i = messages.length - 1; i >= start; i--) {
                Message message = messages[i];
                String sender = message.getFrom() == null || message.getFrom().length == 0
                        ? "unknown" : message.getFrom()[0].toString();
                String subject = message.getSubject() == null ? "" : message.getSubject();
                String body = extractText(message);
                if (body.length() > 2000) {
                    body = body.substring(0, 2000) + "...";
                }
                String threadId = message.getHeader("Message-ID") == null
                        ? "" : message.getHeader("Message-ID")[0];
                result.add(new EmailMessage(sender, userEmail, subject, body, threadId));
            }
            return result;
        } catch (Exception e) {
            throw new MailException("IMAP fetch failed: " + e.getMessage(), e);
        } finally {
            closeQuietly(inbox, store);
        }
    }

    private static void closeQuietly(Folder folder, Store store) {
        if (folder != null) {
            try {
                folder.close(false);
            } catch (Exception ignored) {
                // Best effort.
            }
        }
        if (store != null) {
            try {
                store.close();
            } catch (Exception ignored) {
                // Best effort.
            }
        }
    }

    /**
     * Extracts readable text from a message. JavaMail decodes transfer
     * encodings (quoted-printable, base64) and charsets through
     * {@link Part#getContent()}. The method walks multipart messages, prefers
     * {@code text/plain}, falls back to cleaned {@code text/html}, and skips
     * attachments.
     */
    static String extractText(Part part) {
        try {
            if (isAttachment(part)) {
                return "";
            }
            Object content = part.getContent();
            if (content instanceof Multipart multipart) {
                String htmlFallback = "";
                for (int i = 0; i < multipart.getCount(); i++) {
                    Part child = multipart.getBodyPart(i);
                    String text = extractText(child);
                    if (text.isBlank()) {
                        continue;
                    }
                    if (child.isMimeType("text/plain")) {
                        return text;
                    }
                    if (htmlFallback.isBlank()) {
                        htmlFallback = text;
                    }
                }
                return htmlFallback;
            }
            if (content instanceof Part nested) {
                return extractText(nested);
            }
            if (content instanceof String text) {
                String mimeType = part.getContentType() == null
                        ? "" : part.getContentType().toLowerCase();
                String normalized = text.strip();
                boolean html = mimeType.contains("text/html") || looksLikeHtml(normalized);
                return html ? stripHtml(normalized) : normalized;
            }
        } catch (Exception ignored) {
            // A malformed part should not break the whole inbox.
        }
        return "";
    }

    private static boolean isAttachment(Part part) throws Exception {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
    }

    private static boolean looksLikeHtml(String text) {
        return text.startsWith("<") && text.contains(">");
    }

    private static String stripHtml(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n\n")
                .strip();
    }
}

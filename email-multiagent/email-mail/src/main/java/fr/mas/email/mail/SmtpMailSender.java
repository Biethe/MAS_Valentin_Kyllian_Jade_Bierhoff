package fr.mas.email.mail;

import fr.mas.email.config.AppConfig;
import fr.mas.email.model.EmailMessage;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/** Sends an approved reply through SMTP. */
public final class SmtpMailSender implements MailSender {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean starttls;
    private final boolean ssl;

    public SmtpMailSender(AppConfig config) {
        this.host = config.get("smtp.host", "smtp.gmail.com");
        this.port = config.getInt("smtp.port", 587);
        this.username = config.get("smtp.username", config.get("imap.username", ""));
        this.password = config.get("smtp.password", config.get("imap.password", ""));
        this.starttls = config.get("smtp.starttls", "true").equalsIgnoreCase("true");
        this.ssl = config.get("smtp.ssl", "false").equalsIgnoreCase("true");
    }

    @Override
    public void send(EmailMessage original, String reply) {
        if (username.isBlank() || password.isBlank()) {
            throw new MailException("SMTP credentials are not configured. Set smtp.username and smtp.password.");
        }
        if (reply == null || reply.isBlank()) {
            throw new MailException("The reply is empty.");
        }

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.connectiontimeout", "30000");
        props.put("mail.smtp.timeout", "30000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(original.getSender()));
            message.setSubject(subject(original.getSubject()));
            message.setText(reply);
            Transport.send(message);
        } catch (MessagingException e) {
            throw new MailException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    private static String subject(String original) {
        if (original == null || original.isBlank()) {
            return "Re: your message";
        }
        String trimmed = original.trim();
        return trimmed.toLowerCase().startsWith("re:") ? trimmed : "Re: " + trimmed;
    }
}

package fr.mas.email.mail;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImapMailClientTextTest {

    private static MimeMessage emptyMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void prefersPlainTextOverHtmlInMultipartAlternative() throws Exception {
        MimeMultipart multipart = new MimeMultipart("alternative");

        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Bonjour, ceci est le texte.", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Bonjour, <b>ceci</b> est le texte.</p>", "text/html; charset=UTF-8");

        multipart.addBodyPart(plain);
        multipart.addBodyPart(html);

        MimeMessage message = emptyMessage();
        message.setContent(multipart);

        assertEquals("Bonjour, ceci est le texte.", ImapMailClient.extractText(message));
    }

    @Test
    void decodesQuotedPrintableAndCharset() throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setText("Café déjà vu", "UTF-8", "quoted-printable");

        assertEquals("Café déjà vu", ImapMailClient.extractText(part));
    }

    @Test
    void stripsHtmlWhenThereIsNoPlainTextPart() throws Exception {
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<html><body><p>Hello &amp; welcome</p><br>Next line</body></html>",
                "text/html; charset=UTF-8");

        String text = ImapMailClient.extractText(html);

        assertTrue(text.contains("Hello & welcome"), "actual: [" + text + "]");
        assertTrue(text.contains("Next line"));
        assertFalse(text.contains("<p>"));
        assertFalse(text.contains("&amp;"));
    }

    @Test
    void skipsAttachmentsInMixedMessages() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");

        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("Attachment content", "UTF-8");
        attachment.setFileName("notes.txt");
        attachment.setDisposition(Part.ATTACHMENT);

        MimeBodyPart body = new MimeBodyPart();
        body.setText("This is the real body.", "UTF-8");

        mixed.addBodyPart(attachment);
        mixed.addBodyPart(body);

        MimeMessage message = emptyMessage();
        message.setContent(mixed);

        assertEquals("This is the real body.", ImapMailClient.extractText(message));
    }
}

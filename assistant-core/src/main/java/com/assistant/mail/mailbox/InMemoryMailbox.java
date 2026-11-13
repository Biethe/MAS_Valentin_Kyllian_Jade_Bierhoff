package com.assistant.mail.mailbox;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.mail.User;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.service.mcp.MailClientMcp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/** A mailbox held in memory: searchable, and drafts only. Nothing here can send a mail. */
public final class InMemoryMailbox implements MailClientMcp {
    private final List<Email> mails = new ArrayList<>();
    private final List<Draft> drafts = new ArrayList<>();
    private final AtomicLong nextDraft = new AtomicLong(1000);

    public InMemoryMailbox(List<Email> mails) {
        if (mails != null) this.mails.addAll(mails);
    }

    /** The mailbox the demo mode ships with. */
    public static InMemoryMailbox demo() {
        Email email = new Email();
        email.setId(1L);
        email.setThreadId("t1");
        email.setSender(new User("u-ada", "Ada", null, "Lovelace", "ada@u.test"));
        email.setRecipients(new ArrayList<>(List.of("moi@u.test")));
        email.setSubject("RDV");
        email.setBody("Réunion vendredi à 10h.");
        return new InMemoryMailbox(List.of(email));
    }

    /** Swaps the mails (for example after a fresh IMAP fetch). Saved drafts are kept. */
    public synchronized void replaceMails(List<Email> fresh) {
        mails.clear();
        if (fresh != null) mails.addAll(fresh);
    }

    public synchronized List<Email> all() { return List.copyOf(mails); }

    public List<Draft> drafts() { return List.copyOf(drafts); }

    @Override
    public List<Email> retrieveRelevantEmails(String query) {
        String[] words = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        List<Email> hits = new ArrayList<>();
        for (Email email : mails) {
            String haystack = text(email);
            boolean all = true;
            for (String word : words) {
                if (!word.isBlank() && !haystack.contains(word)) { all = false; break; }
            }
            if (all) hits.add(email);
        }
        return hits;
    }

    @Override
    public List<EmailThread> retrieveRelevantThreads(String query) {
        List<EmailThread> threads = new ArrayList<>();
        for (Email email : retrieveRelevantEmails(query)) {
            EmailThread thread = readThread(email.getThreadId());
            if (thread != null && threads.stream().noneMatch(t -> t.getThreadId().equals(thread.getThreadId()))) {
                threads.add(thread);
            }
        }
        return threads;
    }

    @Override
    public List<AttachmentFile> retrieveAttachments(Email email) {
        return email == null ? List.of() : email.getAttachments();
    }

    @Override
    public List<AttachmentFile> retrieveAttachments(EmailThread thread) {
        List<AttachmentFile> files = new ArrayList<>();
        if (thread != null) for (Email email : thread.getEmails()) files.addAll(email.getAttachments());
        return files;
    }

    @Override
    public Email readMail(String id) {
        for (Email email : mails) {
            if (String.valueOf(email.getId()).equals(id == null ? "" : id.strip())) return email;
        }
        return null;
    }

    @Override
    public EmailThread readThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        EmailThread thread = new EmailThread();
        thread.setThreadId(threadId);
        for (Email email : mails) if (threadId.equals(email.getThreadId())) thread.getEmails().add(email);
        return thread.getEmails().isEmpty() ? null : thread;
    }

    @Override
    public synchronized Long createDraft(List<String> to, List<String> cc, List<String> bcc, String subject,
                                         String body, Long inReplyTo) {
        Draft draft = new Draft();
        draft.setId(nextDraft.incrementAndGet());
        draft.setRecipients(to == null ? new ArrayList<>() : new ArrayList<>(to));
        draft.setCc(cc == null ? new ArrayList<>() : new ArrayList<>(cc));
        draft.setBcc(bcc == null ? new ArrayList<>() : new ArrayList<>(bcc));
        draft.setSubject(subject);
        draft.setBody(body);
        drafts.add(draft);
        return draft.getId();
    }

    private static String text(Email email) {
        String sender = email.getSender() == null ? "" : email.getSender().getFullName() + " "
                + (email.getSender().getEmail() == null ? "" : email.getSender().getEmail());
        return (sender + " " + email.getSubject() + " " + email.getBody()).toLowerCase(Locale.ROOT);
    }
}

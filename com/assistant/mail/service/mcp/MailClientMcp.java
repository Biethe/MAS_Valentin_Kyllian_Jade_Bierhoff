package com.assistant.mail.service.mcp;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import java.util.List;

public interface MailClientMcp {
    List<Email> retrieveRelevantEmails(String query);
    List<EmailThread> retrieveRelevantThreads(String query);
    List<AttachmentFile> retrieveAttachments(Email email);
    List<AttachmentFile> retrieveAttachments(EmailThread thread);

    /** Search hits. A body may be only a snippet until {@link #readMail}. */
    default List<Email> searchMail(String query) { return retrieveRelevantEmails(query); }

    /** Full mail. {@code id} is the id search already returned. */
    default Email readMail(String id) { return null; }

    /** Full thread. {@code threadId} is one search already returned. */
    default EmailThread readThread(String threadId) { return null; }

    /** True when {@code id} was only a search snippet. */
    default boolean unread(Long id) { return false; }

    /** Mailbox id that stays stable across runs. Falls back to the local id. */
    default String remoteId(Long id) { return id == null ? "" : Long.toString(id); }

    /** Places one draft in the drafts folder. It is never sent. A compose passes no inReplyTo. */
    Long createDraft(List<String> to, List<String> cc, List<String> bcc, String subject, String body, Long inReplyTo);
}

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
}

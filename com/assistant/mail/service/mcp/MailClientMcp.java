package com.assistant.mail.service;

import com.assistant.mail.model.AttachmentFile;
import com.assistant.mail.model.Email;
import com.assistant.mail.model.EmailThread;
import java.util.List;

public interface MailClientMcp {
    List<Email> retrieveRelevantEmails(String query);
    List<EmailThread> retrieveRelevantThreads(String query);
    List<AttachmentFile> retrieveAttachments(Email email);
    List<AttachmentFile> retrieveAttachments(EmailThread thread);
}

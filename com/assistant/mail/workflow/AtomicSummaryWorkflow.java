package com.assistant.mail.workflow;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.service.LlmAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class AtomicSummaryWorkflow {

    private final MailClientMcp mailClient;
    private final LlmAgentClient llmClient;
    private final String systemPrompt;

    public AtomicSummaryWorkflow(MailClientMcp mailClient, LlmAgentClient llmClient, String systemPrompt) {
        this.mailClient = mailClient;
        this.llmClient = llmClient;
        this.systemPrompt = systemPrompt;
    }

    public Summary execute(String instruction) {
        StringBuilder finalInfoForSummarizer = new StringBuilder();
        Instant deadlineWindow = Instant.now().plus(48, ChronoUnit.HOURS);

        List<Email> emails = mailClient.retrieveRelevantEmails(instruction);
        List<EmailThread> threads = mailClient.retrieveRelevantThreads(instruction);

        for (Email email : emails) {
            processEmail(email, finalInfoForSummarizer, deadlineWindow);
        }

        for (EmailThread thread : threads) {
            processThread(thread, finalInfoForSummarizer, deadlineWindow);
        }

        String finalPrompt = systemPrompt + "\nInstruction: " + instruction + "\nData: " + finalInfoForSummarizer;
        return llmClient.generateFinalSummary(finalPrompt);
    }

    private void processEmail(Email email, StringBuilder accumulator, Instant deadlineWindow) {
        String senderName = email.getSender() != null ? email.getSender().getFullName() : "Unknown";
        accumulator.append("Sender: ").append(senderName).append("\n");

        String summarizedBody = llmClient.summarizeText(email.getBody());
        accumulator.append("Body Summary: ").append(summarizedBody).append("\n");

        if (email.hasCloseDeadline(deadlineWindow)) {
            accumulator.append("URGENT DEADLINE: ").append(email.getDeadline()).append("\n");
        }

        List<AttachmentFile> files = email.getAttachments();
        for (AttachmentFile file : files) {
            String fileSummary = llmClient.summarizeAttachment(file);
            accumulator.append("File [").append(file.getName()).append("]: ").append(fileSummary).append("\n");
        }
    }

    private void processThread(EmailThread thread, StringBuilder accumulator, Instant deadlineWindow) {
        accumulator.append("--- Thread: ").append(thread.getThreadId()).append(" ---\n");
        for (Email email : thread.getEmails()) {
            processEmail(email, accumulator, deadlineWindow);
        }
    }
}

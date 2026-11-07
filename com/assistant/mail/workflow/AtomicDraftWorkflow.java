package com.assistant.mail.workflow;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.mail.EmailThread;
import com.assistant.mail.service.agent.PlannerAgentClient;
import com.assistant.mail.service.agent.DrafterAgentClient;
import com.assistant.mail.service.agent.SummarizerAgentClient;
import com.assistant.mail.service.agent.ValidatorAgentClient;
import com.assistant.mail.service.mcp.MailClientMcp;
import com.assistant.mail.prompt.PlannerSystemPrompt;
import com.assistant.mail.prompt.DrafterSystemPrompt;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
import com.assistant.mail.prompt.ValidatorSystemPrompt;
import java.util.ArrayList;
import java.util.List;

public class AtomicDraftWorkflow {

    private final MailClientMcp mailClient;
    private final PlannerAgentClient plannerAgentClient;
    private final DrafterAgentClient drafterAgentClient;
    private final SummarizerAgentClient summarizerAgentClient;
    private final ValidatorAgentClient validatorAgentClient;
    private final PlannerSystemPrompt plannerSystemPrompt;
    private final DrafterSystemPrompt drafterSystemPrompt;
    private final SummarizerSystemPrompt summarizerSystemPrompt;
    private final ValidatorSystemPrompt validatorSystemPrompt;

    public AtomicDraftWorkflow(MailClientMcp mailClient, LlmAgentClient llmClient, String systemPrompt) {
        this.mailClient = mailClient;
        this.plannerAgentClient = plannerAgentClient;
        this.drafterAgentClient = drafterAgentClient;
        this.summarizerAgentClient = summarizerAgentClient;
        this.validatorAgentClient = validatorAgentClient;
        this.plannerSystemPrompt = plannerSystemPrompt;
        this.drafterSystemPrompt = drafterSystemPrompt;
        this.summarizerSystemPrompt = summarizerSystemPrompt;
        this.validatorSystemPrompt = validatorSystemPrompt;
    }

    public Draft execute(String instruction) {
        StringBuilder finalInfoForDrafter = new StringBuilder();

        List<Email> emails = mailClient.retrieveRelevantEmails(instruction);
        List<EmailThread> threads = mailClient.retrieveRelevantThreads(instruction);

        List<AttachmentFile> emailFiles = new ArrayList<>();
        for (Email email : emails) {
            emailFiles.addAll(mailClient.retrieveAttachments(email));
        }

        List<AttachmentFile> threadFiles = new ArrayList<>();
        for (EmailThread thread : threads) {
            threadFiles.addAll(mailClient.retrieveAttachments(thread));
        }

        for (Email email : emails) {
            finalInfoForDrafter.append(extractRelevantInfo(email)).append("\n");
        }
        for (EmailThread thread : threads) {
            finalInfoForDrafter.append(extractRelevantInfo(thread)).append("\n");
        }
        for (AttachmentFile file : emailFiles) {
            finalInfoForDrafter.append(extractRelevantInfo(file)).append("\n");
        }
        for (AttachmentFile file : threadFiles) {
            finalInfoForDrafter.append(extractRelevantInfo(file)).append("\n");
        }

        String finalPrompt = systemPrompt + "\nInstruction: " + instruction + "\nContext: " + finalInfoForDrafter;
        return llmClient.generateDraft(finalPrompt);
    }

    private String extractRelevantInfo(Object element) {
        if (element instanceof Email e) {
            return "Email from " + e.getSender().getFullName() + ": " + e.getBody();
        } else if (element instanceof EmailThread t) {
            return "Thread ID " + t.getThreadId() + " containing " + t.getEmails().size() + " messages.";
        } else if (element instanceof AttachmentFile f) {
            return "Attachment: " + f.getName();
        }
        return "";
    }
}

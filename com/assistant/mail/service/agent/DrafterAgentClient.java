package com.assistant.mail.service.agent;

import com.assistant.mail.model.output.Draft;
import com.assistant.mail.prompt.DrafterSystemPrompt;

public interface DrafterAgentClient {
    Draft generateDraft(DrafterSystemPrompt drafterSystemPrompt, String prompt);
}

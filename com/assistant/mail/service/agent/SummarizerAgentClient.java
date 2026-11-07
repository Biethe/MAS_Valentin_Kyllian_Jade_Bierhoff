package com.assistant.mail.service;

import com.assistant.mail.model.output.Summary;
import com.assistant.mail.prompt.SummarizerSystemPrompt;
public interface SummarizerAgentClient {
    String generateFinalSummary(SummarizerSystemPrompt summarizerSystemPrompt, String prompt);
}

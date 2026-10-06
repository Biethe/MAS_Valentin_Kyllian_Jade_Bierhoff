package com.assistant.mail.service;

import com.assistant.mail.model.Draft;
import com.assistant.mail.model.Summary;
import com.assistant.mail.model.Instruction;
import com.assistant.mail.model.Information;
import com.assistant.mail.prompt.ValidatorSystemPrompt;

public interface ValidatorAgentClient {
    boolean validateDraft(ValidatorSystemPrompt validatorSystemPrompt, String brief, Draft draft);
    boolean validateSummary(ValidatorSystemPrompt validatorSystemPrompt, String brief, Summary summary);
    boolean validatePlannerInstructions(ValidatorSystemPrompt validatorSystemPrompt, List<Instruction> instructions);
    boolean validatePlannerInformation(ValidatorSystemPrompt validatorSystemPrompt, List<Information> information);
}

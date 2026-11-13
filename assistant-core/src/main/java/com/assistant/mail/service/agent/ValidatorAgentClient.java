package com.assistant.mail.service.agent;

import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.output.Summary;
import com.assistant.mail.model.plan.Instruction;
import com.assistant.mail.model.fact.Information;
import com.assistant.mail.prompt.ValidatorSystemPrompt;

import java.util.List;

public interface ValidatorAgentClient {
    boolean validateDraft(ValidatorSystemPrompt validatorSystemPrompt, String brief, Draft draft);
    boolean validateSummary(ValidatorSystemPrompt validatorSystemPrompt, String brief, Summary summary);
    boolean validatePlannerInstructions(ValidatorSystemPrompt validatorSystemPrompt, List<Instruction> instructions);
    boolean validatePlannerInformation(ValidatorSystemPrompt validatorSystemPrompt, List<Information> information);
}

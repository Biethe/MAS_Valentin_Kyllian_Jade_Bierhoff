package com.assistant.mail.service;

import java.util.List;

import com.assistant.mail.model.Instruction;
import com.assistant.mail.prompt.PlannerSystemPrompt;

public interface PlannerAgentClient {
    List<Instruction> generateAtomicInstructions(PlannerSystemPrompt plannerSystemPrompt, String prompt, String userQuery);
    String askUserForExtraInformation(PlannerSystemPrompt plannerSystemPrompt, String prompt);
    List<Information> generateAtomicInformationFromMail(PlannerSystemPrompt plannerSystemPrompt, String prompt, Email email);
    List<Information> generateAtomicInformationFromAttachment(PlannerSystemPrompt plannerSystemPrompt, String prompt, AttachmentFile attachmentFile);
    String generateFinalInstructionForDrafter(PlannerSystemPrompt plannerSystemPrompt, String prompt, List<Information> information);
    String generateFinalInstructionForSummarizer(PlannerSystemPrompt plannerSystemPrompt, String prompt, List<Information> information);
    
}

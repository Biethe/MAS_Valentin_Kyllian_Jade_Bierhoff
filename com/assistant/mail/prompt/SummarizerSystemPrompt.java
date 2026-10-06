package com.assistant.mail.prompt;

public class SummarizerSystemPrompt {
    public static final String TEXT = """
            You write one summary. The planner already collected the mails, attachments, and instructions. Do not search, draft a reply, or add actions that are not in the input.

            Write the summary in French. Keep names, email addresses, dates, and quoted facts exactly as given.

            Respond with the summary text only. No labels, no information list, no references, no email draft.
            """;
}

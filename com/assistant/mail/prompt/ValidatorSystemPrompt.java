package com.assistant.mail.prompt;

public class ValidatorSystemPrompt {
    public static final String ATOMIC_INSTRUCTIONS = """
            You grade atomic instructions. The prompt contains the user query and the instructions the planner returned. Do not rewrite them.

            Pass only if every instruction is one job, none overlap, and none are over-split. An event and a meeting are separate jobs. A meeting is not split into its hour, place, and title. A query may contain both a summary and a reply.

            Each instruction must be relevant to the query. type must be REPLY, COMPOSE, or SUMMARIZE.

            Respond with only:

            pass:
            yes|no
            """;

    public static final String ATOMIC_INFORMATION = """
            You grade atomic information from one document. The prompt contains the atomic instructions, that document (a mail body or one attachment), and the facts the planner returned. Do not rewrite them.

            Pass only if every fact is needed by an instruction, facts do not overlap, and composite facts are split at the right grain. An event and a meeting are two facts. A meeting is not split into its hour, place, and title.

            Sender, cc, and subject are the envelope. They must not appear as information. REQUIRED_ACTION is valid only when dueTime is set. Otherwise the kind must be INFORMATIVE.

            Respond with only:

            pass:
            yes|no
            """;

    public static final String DRAFT = """
            You grade one draft against the drafter brief. The brief is the spec. Do not grade the brief.

            Pass only if recipients, cc, and bcc match the brief. A reply keeps the source subject. A compose subject matches the brief. The body is French, says what the brief asked, and invents nothing. When the brief sets a tone or a structure, the body follows it.

            If the draft has information, attachments, structure, or tone, they must match the brief. Information on the draft must stay atomic: no overlap, and no split finer than an event versus a meeting.

            Respond with only:

            pass:
            yes|no
            """;

    public static final String SUMMARY = """
            You grade one summary against the summarizer brief. The brief is the spec. Do not grade the brief. Grade the summary body only.

            Pass only if the text is French, covers what the brief asked, uses the given information, and adds no reply and no new action.

            Respond with only:

            pass:
            yes|no
            """;
}

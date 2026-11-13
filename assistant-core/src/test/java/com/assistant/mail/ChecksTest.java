package com.assistant.mail;

import com.assistant.mail.service.mcp.MailboxClient;
import com.assistant.mail.workflow.ChecklistProgress;
import com.assistant.mail.workflow.PlannerMemory;
import com.assistant.mail.workflow.PlannerWorkflowCheck;
import com.assistant.mail.workflow.SummaryWorkflowCheck;
import com.assistant.mail.workflow.ToolGate;
import com.assistant.mail.workflow.TurnCheck;
import com.assistant.mail.workflow.WriterWorkflowCheck;
import org.junit.jupiter.api.Test;

/** The self-checks the code already shipped with. They use `assert`, which surefire enables. */
class ChecksTest {
    @Test void planner() throws Exception { PlannerWorkflowCheck.main(new String[0]); }
    @Test void writer() throws Exception { WriterWorkflowCheck.main(new String[0]); }
    @Test void summary() throws Exception { SummaryWorkflowCheck.main(new String[0]); }
    @Test void turn() { TurnCheck.main(new String[0]); }
    @Test void memory() { PlannerMemory.main(new String[0]); }
    @Test void checklist() { ChecklistProgress.main(new String[0]); }
    @Test void toolGate() { ToolGate.main(new String[0]); }
    @Test void mailboxClientParsing() { MailboxClient.main(new String[0]); }
}

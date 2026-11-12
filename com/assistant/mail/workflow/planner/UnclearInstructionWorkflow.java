package com.assistant.mail.workflow.planner;

import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.Task;
import com.assistant.mail.service.UserValidationChannel;
import com.assistant.mail.service.mcp.MailClientMcp;

import java.util.ArrayList;
import java.util.List;

public class UnclearInstructionWorkflow {

    private final MailClientMcp mailClient;
    private final UserValidationChannel validationChannel;

    public UnclearInstructionWorkflow(MailClientMcp mailClient,
                                    UserValidationChannel validationChannel) {
        this.mailClient = mailClient;
        this.validationChannel = validationChannel;
    }

    public List<Draft> execute(String unclearInstruction) {
        List<Email> potentialMail = mailClient.retrieveRelevantEmails(unclearInstruction);
        List<Task> potentialTasks = createPotentialTasks(potentialMail, unclearInstruction);

        for (Task task : potentialTasks) {
            validationChannel.askUserToValidate(task);
        }

        return List.of();
    }

    private List<Task> createPotentialTasks(List<Email> potentialMail, String unclearInstruction) {
        List<Task> tasks = new ArrayList<>();
        int count = 1;
        for (Email mail : potentialMail) {
            tasks.add(new Task(
                "task-" + count++,
                "Follow up on email: " + mail.getSubject(),
                "Resolve instruction '" + unclearInstruction + "' for email ID: " + mail.getId(),
                mail.getId()
            ));
        }
        return tasks;
    }
}

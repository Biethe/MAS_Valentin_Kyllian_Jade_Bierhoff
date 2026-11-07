package com.assistant.mail.workflow;

import com.assistant.mail.model.output.Draft;
import com.assistant.mail.model.mail.Email;
import com.assistant.mail.model.Task;
import com.assistant.mail.service.MailClientMcp;
import com.assistant.mail.service.UserValidationChannel;

import java.util.ArrayList;
import java.util.List;

public class UnclearInstructionWorkflow {

    private final MailClientMcp mailClient;
    private final UserValidationChannel validationChannel;
    private final AtomicDraftWorkflow draftSolver;

    public UnclearInstructionWorkflow(MailClientMcp mailClient,
                                    UserValidationChannel validationChannel,
                                    AtomicDraftWorkflow draftSolver) {
        this.mailClient = mailClient;
        this.validationChannel = validationChannel;
        this.draftSolver = draftSolver;
    }

    public List<Draft> execute(String unclearInstruction) {
        List<Email> potentialMail = mailClient.retrieveRelevantEmails(unclearInstruction);
        List<Task> potentialTasks = createPotentialTasks(potentialMail, unclearInstruction);

        List<Task> validatedTasks = new ArrayList<>();
        for (Task task : potentialTasks) {
            boolean validation = validationChannel.askUserToValidate(task);
            if (validation) {
                validatedTasks.add(task);
            }
        }

        List<Draft> generatedDrafts = new ArrayList<>();
        for (Task task : validatedTasks) {
            String instruction = extractInstruction(task);
            Draft draft = draftSolver.execute(instruction);
            generatedDrafts.add(draft);
        }

        return generatedDrafts;
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

    private String extractInstruction(Task task) {
        return task.getDerivedInstruction();
    }
}

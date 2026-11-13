package com.assistant.mail.service.userAction;

import com.assistant.mail.model.Task;

public interface UserValidationChannel {
    boolean askUserToValidate(Task task);
}

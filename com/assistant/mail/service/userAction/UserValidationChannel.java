package com.assistant.mail.service;

import com.assistant.mail.model.Task;

public interface UserValidationChannel {
    boolean askUserToValidate(Task task);
}

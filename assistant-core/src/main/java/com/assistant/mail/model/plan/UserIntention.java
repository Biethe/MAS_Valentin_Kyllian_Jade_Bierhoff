package com.assistant.mail.model.plan;

import com.assistant.mail.model.mail.AttachmentFile;
import com.assistant.mail.model.mail.User;

import java.util.ArrayList;
import java.util.List;

public class UserIntention {
    private User user;
    private List<Instruction> instructions = new ArrayList<>();
    private Context context;
    private List<AttachmentFile> attachments = new ArrayList<>();

    public UserIntention() {}

    public UserIntention(User user, List<Instruction> instructions, Context context) {
        this.user = user;
        this.instructions = instructions;
        this.context = context;
    }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public List<Instruction> getInstructions() { return instructions; }
    public void setInstructions(List<Instruction> instructions) { this.instructions = instructions; }

    public Context getContext() { return context; }
    public void setContext(Context context) { this.context = context; }

    public List<AttachmentFile> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentFile> attachments) { this.attachments = attachments; }
}

package com.assistant.mail.model;

import java.util.ArrayList;
import java.util.List;

public class UserIntention {
    private User user;
    private List<Instruction> instructions = new ArrayList<>();
    private Context context;

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
}

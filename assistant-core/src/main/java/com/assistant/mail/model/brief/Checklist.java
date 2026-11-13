package com.assistant.mail.model.brief;

import java.util.ArrayList;
import java.util.List;

public class Checklist {
    
    private List<ChecklistItem> items = new ArrayList<>();

    public Checklist() {}

    public List<ChecklistItem> getItems() { return items; }
    public void setItems(List<ChecklistItem> items) {
        this.items = items == null ? new ArrayList<>() : items;
    }
}

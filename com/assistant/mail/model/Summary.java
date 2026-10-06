package com.assistant.mail.model;

import java.util.ArrayList;
import java.util.List;

public class Summary {
    private String body;
    private List<Information> information = new ArrayList<>();
    private List<String> references = new ArrayList<>(); // email IDs, threads, attached file names

    public Summary() {}

    public Summary(String body, List<Information> information, List<String> references) {
        this.body = body;
        this.information = information;
        this.references = references;
    }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public List<Information> getInformation() { return information; }
    public void setInformation(List<Information> information) { this.information = information; }

    public List<String> getReferences() { return references; }
    public void setReferences(List<String> references) { this.references = references; }
}

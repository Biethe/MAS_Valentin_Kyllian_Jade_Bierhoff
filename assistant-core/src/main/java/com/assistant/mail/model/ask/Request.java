package com.assistant.mail.model.ask;

import com.assistant.mail.model.mail.AttachmentFile;

import java.util.ArrayList;
import java.util.List;

public class Request {
    private String text;
    private List<AttachmentFile> files = new ArrayList<>();

    public Request() {}

    public Request(String text, List<AttachmentFile> files) {
        this.text = text;
        this.files = files == null ? new ArrayList<>() : files;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public List<AttachmentFile> getFiles() { return files; }
    public void setFiles(List<AttachmentFile> files) {
        this.files = files == null ? new ArrayList<>() : files;
    }
}

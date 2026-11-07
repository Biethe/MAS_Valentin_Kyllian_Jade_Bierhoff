package com.assistant.mail.model.mail;

public class AttachmentFile {
    private String name;
    private String mimeType;
    private byte[] data;

    public AttachmentFile(String name, String mimeType, byte[] data) {
        this.name = name;
        this.mimeType = mimeType;
        this.data = data;
    }

    public String getName() { return name; }
    public String getMimeType() { return mimeType; }
    public byte[] getData() { return data; }
}

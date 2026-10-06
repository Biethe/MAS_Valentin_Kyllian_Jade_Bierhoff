package com.assistant.mail.model;

public class User {
    private String id;
    private String firstName;
    private String middleName;
    private String lastName;
    private String email;

    public User(String id, String firstName, String middleName, String lastName, String email) {
        this.id = id;
        this.firstName = firstName;
        this.middleName = middleName;
        this.lastName = lastName;
        this.email = email;
    }

    public String getId() { return id; }
    public String getFirstName() { return firstName; }
    public String getMiddleName() { return middleName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }

    public String getFullName() {
        StringBuilder name = new StringBuilder();
        appendPart(name, firstName);
        appendPart(name, middleName);
        appendPart(name, lastName);
        return name.toString();
    }

    private static void appendPart(StringBuilder name, String part) {
        if (part == null || part.isBlank()) return;
        if (name.length() > 0) name.append(' ');
        name.append(part);
    }
}

package com.assistant.mail.service;

import java.util.ArrayList;
import java.util.List;

/** Steps of the turn the UI is showing. Also printed. */
public final class Progress {
    private static final List<String> LINES = new ArrayList<>();

    private Progress() {}

    public static void note(String line) {
        if (line == null || line.isBlank()) return;
        String text = line.strip();
        System.out.println(text);
        synchronized (LINES) {
            LINES.add(text);
            if (LINES.size() > 40) LINES.remove(0);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static List<String> lines() {
        synchronized (LINES) {
            return List.copyOf(LINES);
        }
    }

    public static void main(String[] args) {
        clear();
        note("Reading the request");
        note("  ");
        note("Searching: Ada");
        List<String> lines = lines();
        if (lines.size() != 2 || !lines.get(1).equals("Searching: Ada")) throw new IllegalStateException(lines.toString());
        clear();
        System.out.println("Progress ok");
    }
}

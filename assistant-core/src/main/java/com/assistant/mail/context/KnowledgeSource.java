package com.assistant.mail.context;

import java.util.List;

/** Background the Writer and the Summarizer may use: who the recipient is, and what their profile knows. */
public interface KnowledgeSource {
    /**
     * @param recipient address of the person the mail goes to or comes from (may be blank)
     * @param role      the planner's recipient role (professor, student, ...), may be blank
     * @param query     the instruction and facts, used to pick passages
     * @return short lines, each one a contact card or a retrieved passage; empty when nothing applies
     */
    List<String> knowledgeFor(String recipient, String role, String query);
}

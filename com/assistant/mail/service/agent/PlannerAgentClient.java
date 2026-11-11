package com.assistant.mail.service.agent;

public interface PlannerAgentClient {
    String complete(String system, String user);

    /** Empty when this client has no embedding model. */
    default float[] embed(String text) { return new float[0]; }
}

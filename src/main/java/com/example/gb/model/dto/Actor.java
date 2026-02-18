package com.example.gb.model.dto;

public record Actor(
        String type,   // "agent" | "human" | "system"
        String id      // optional
) {
    public static Actor agent(String id) { return new Actor("agent", id); }
    public static Actor human(String id) { return new Actor("human", id); }
    public static Actor system() { return new Actor("system", null); }
}

package dev.jev.npc.ai;

public record Candidate(String id, String description) {
    public Candidate {
        if (id == null || id.isBlank() || description == null || description.isBlank()) {
            throw new IllegalArgumentException("Candidate needs an id and a description");
        }
    }
}

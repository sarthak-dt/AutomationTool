package com.stellar.platform.storage.metadata;

/** Thrown when a project name conflicts with an existing project (case/whitespace insensitive). */
public final class DuplicateProjectNameException extends RuntimeException {
    private final String attemptedName;
    private final String conflictingProjectId;

    public DuplicateProjectNameException(String attemptedName, String conflictingProjectId) {
        super(friendlyMessage(attemptedName));
        this.attemptedName = attemptedName == null ? "" : attemptedName.trim();
        this.conflictingProjectId = conflictingProjectId == null ? "" : conflictingProjectId;
    }

    public String attemptedName() {
        return attemptedName;
    }

    public String conflictingProjectId() {
        return conflictingProjectId;
    }

    public static String friendlyMessage(String attemptedName) {
        String display = attemptedName == null || attemptedName.isBlank() ? "that name" : attemptedName.trim();
        return "A project named \"" + display + "\" already exists.\nChoose a different project name.";
    }
}

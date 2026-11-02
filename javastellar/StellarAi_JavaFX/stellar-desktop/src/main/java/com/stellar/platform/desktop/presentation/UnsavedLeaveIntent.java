package com.stellar.platform.desktop.presentation;

/** Why the shell is asking about unsaved durable changes. */
public enum UnsavedLeaveIntent {
    CLOSE("Save & Close", "Don't Save"),
    SWITCH("Save & Switch", "Don't Save & Switch"),
    EXIT("Save & Exit", "Don't Save");

    private final String saveLabel;
    private final String discardLabel;

    UnsavedLeaveIntent(String saveLabel, String discardLabel) {
        this.saveLabel = saveLabel;
        this.discardLabel = discardLabel;
    }

    public String saveLabel() {
        return saveLabel;
    }

    public String discardLabel() {
        return discardLabel;
    }
}

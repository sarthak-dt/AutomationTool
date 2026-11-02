package com.stellar.platform.processing.drive;

import java.util.List;

public record DriveSignalHealthSummary(List<DriveSignalIssue> issues) {
    public DriveSignalHealthSummary {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static DriveSignalHealthSummary none() {
        return new DriveSignalHealthSummary(List.of());
    }

    public int count() {
        return issues.size();
    }
}

package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.WorkspaceFile;

import java.util.List;
import java.util.Locale;

/**
 * Which measurement families a project contains. Built from resident file kinds or the
 * dashboard type label — never by probing file bytes.
 *
 * <p>DAT and LOG are direct. MF4, ASC and BLF still require a definition. A project that
 * contains both is mixed: the direct source does not make the definition source direct,
 * and the definition source does not make the direct source require a DBC.</p>
 */
public final class SourceWorkflowProfile {

    private final boolean hasDirect;
    private final boolean hasDefinitionRequired;

    private SourceWorkflowProfile(boolean hasDirect, boolean hasDefinitionRequired) {
        this.hasDirect = hasDirect;
        this.hasDefinitionRequired = hasDefinitionRequired;
    }

    public static SourceWorkflowProfile empty() {
        return new SourceWorkflowProfile(false, false);
    }

    public static SourceWorkflowProfile fromMeasurements(List<WorkspaceFile> files) {
        boolean direct = false;
        boolean definition = false;
        if (files != null) {
            for (WorkspaceFile file : files) {
                if (file == null || !file.kind().measurement()) {
                    continue;
                }
                if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())) {
                    direct = true;
                } else if (MeasurementSourceCapability.requiresDefinition(file.kind())
                        && file.kind().implemented()) {
                    definition = true;
                }
            }
        }
        return new SourceWorkflowProfile(direct, definition);
    }

    /**
     * Dashboard type labels are persisted kind names with the {@code MEASUREMENT_} /
     * {@code DEFINITION_} prefix removed, joined by {@code ·}.
     */
    public static SourceWorkflowProfile fromTypeLabel(String label) {
        boolean direct = false;
        boolean definition = false;
        if (label != null && !label.isBlank()) {
            for (String raw : label.split("[·,]")) {
                switch (raw.trim().toUpperCase(Locale.ROOT)) {
                    case "DAT", "LOG" -> direct = true;
                    case "MF4", "ASC", "BLF" -> definition = true;
                    default -> {
                    }
                }
            }
        }
        return new SourceWorkflowProfile(direct, definition);
    }

    public static SourceWorkflowProfile of(FileKind kind) {
        if (kind == null || !kind.measurement()) {
            return empty();
        }
        return new SourceWorkflowProfile(
                MeasurementSourceCapability.supportsDirectProcessing(kind),
                MeasurementSourceCapability.requiresDefinition(kind) && kind.implemented());
    }

    public boolean hasDirect() {
        return hasDirect;
    }

    public boolean hasDefinitionRequired() {
        return hasDefinitionRequired;
    }

    /** Every measurement is a direct source. Definition-required formats are absent. */
    public boolean directOnly() {
        return hasDirect && !hasDefinitionRequired;
    }
}

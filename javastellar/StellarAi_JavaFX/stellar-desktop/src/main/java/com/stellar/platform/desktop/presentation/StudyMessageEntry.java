package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.StudyMessageKey;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lightweight Study Set membership - identity + DBC definition metadata only.
 * No frames, no decoded samples, no signal sample materialization.
 */
public final class StudyMessageEntry {

    private final StudyMessageKey key;
    private final String definitionDisplayName;
    private final String dbcMessageName;
    private final DefinitionSourceId definitionSourceId;
    private final List<DbcSignalDefinitionView> signalDefinitions;

    public StudyMessageEntry(
            StudyMessageKey key,
            String definitionDisplayName,
            String dbcMessageName,
            DefinitionSourceId definitionSourceId,
            List<DbcSignalDefinitionView> signalDefinitions
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.dbcMessageName = dbcMessageName == null ? "" : dbcMessageName;
        this.definitionSourceId = definitionSourceId;
        this.signalDefinitions = List.copyOf(
                Objects.requireNonNullElse(signalDefinitions, List.of()));
    }

    public static StudyMessageEntry fromRow(CanMessageInventoryRow row) {
        Objects.requireNonNull(row, "row");
        StudyMessageKey key = StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
        String msg = row.dbcMessageDisplay();
        String cleaned = (msg == null || msg.isBlank() || "—".equals(msg)) ? "" : msg;
        return new StudyMessageEntry(
                key,
                row.definitionDisplayName(),
                cleaned,
                row.definitionSourceId().orElse(null),
                row.signalDefinitions()
        );
    }

    public static StudyMessageEntry fromEligible(CanMessageInventoryRow row) {
        return fromRow(row);
    }

    public StudyMessageKey key() {
        return key;
    }

    public String canIdHex() {
        return "0x" + Long.toHexString(key.canId()).toUpperCase(Locale.ROOT);
    }

    public String definitionDisplayName() {
        return definitionDisplayName;
    }

    public String dbcMessageName() {
        return dbcMessageName;
    }

    public String dbcMessageDisplay() {
        return dbcMessageName.isBlank() ? "—" : dbcMessageName;
    }

    public Optional<DefinitionSourceId> definitionSourceId() {
        return Optional.ofNullable(definitionSourceId);
    }

    public int signalCount() {
        return signalDefinitions.size();
    }

    public List<DbcSignalDefinitionView> signalDefinitions() {
        return signalDefinitions;
    }

    /**
     * True when {@code other} would preview exactly the same signal definitions from the same DBC.
     * Used to tell a harmless re-projection apart from a real configuration change.
     */
    public boolean sameProjectionAs(StudyMessageEntry other) {
        if (other == null) {
            return false;
        }
        if (!Objects.equals(definitionSourceId, other.definitionSourceId)
                || !definitionDisplayName.equals(other.definitionDisplayName)
                || !dbcMessageName.equals(other.dbcMessageName)
                || signalDefinitions.size() != other.signalDefinitions.size()) {
            return false;
        }
        for (int i = 0; i < signalDefinitions.size(); i++) {
            if (!sameSignal(signalDefinitions.get(i), other.signalDefinitions.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSignal(DbcSignalDefinitionView left, DbcSignalDefinitionView right) {
        return left.name().equals(right.name())
                && left.startBit() == right.startBit()
                && left.bitLength() == right.bitLength()
                && Objects.equals(left.unit(), right.unit());
    }
}

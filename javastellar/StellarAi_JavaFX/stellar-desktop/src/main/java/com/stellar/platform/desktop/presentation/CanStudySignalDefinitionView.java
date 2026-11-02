package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.StudyMessageKey;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One Study Set projection row: either a CAN-message group header or a signal definition.
 * Presentation-only metadata - no sample decoding.
 */
public final class CanStudySignalDefinitionView {

    private final boolean groupHeader;
    private final StudyMessageKey studyKey;
    private final String canIdHex;
    private final String channelLabel;
    private final String dbcMessageName;
    private final String definitionDisplayName;
    private final DefinitionSourceId definitionSourceId;
    private final int groupSignalCount;
    private final String headerLabel;
    private final DbcSignalDefinitionView signal;

    private CanStudySignalDefinitionView(
            boolean groupHeader,
            StudyMessageKey studyKey,
            String canIdHex,
            String channelLabel,
            String dbcMessageName,
            String definitionDisplayName,
            DefinitionSourceId definitionSourceId,
            int groupSignalCount,
            String headerLabel,
            DbcSignalDefinitionView signal
    ) {
        this.groupHeader = groupHeader;
        this.studyKey = Objects.requireNonNull(studyKey, "studyKey");
        this.canIdHex = canIdHex == null ? "" : canIdHex;
        this.channelLabel = channelLabel == null ? "" : channelLabel;
        this.dbcMessageName = dbcMessageName == null ? "" : dbcMessageName;
        this.definitionDisplayName = definitionDisplayName == null ? "" : definitionDisplayName;
        this.definitionSourceId = definitionSourceId;
        this.groupSignalCount = Math.max(0, groupSignalCount);
        this.headerLabel = headerLabel == null ? "" : headerLabel;
        this.signal = signal;
    }

    public static CanStudySignalDefinitionView groupHeader(StudyMessageEntry entry, boolean showChannelProvenance) {
        Objects.requireNonNull(entry, "entry");
        StudyMessageKey key = entry.key();
        String msg = entry.dbcMessageName().isBlank() ? "—" : entry.dbcMessageName();
        String title = entry.canIdHex() + " · " + msg + " · " + entry.signalCount() + " signals";
        if (showChannelProvenance) {
            title = "Ch" + key.channelId() + " · " + title;
        }
        return new CanStudySignalDefinitionView(
                true,
                key,
                entry.canIdHex(),
                "Ch" + key.channelId(),
                entry.dbcMessageName(),
                entry.definitionDisplayName(),
                entry.definitionSourceId().orElse(null),
                entry.signalCount(),
                title,
                null
        );
    }

    public static CanStudySignalDefinitionView signalRow(StudyMessageEntry entry, DbcSignalDefinitionView signal) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(signal, "signal");
        StudyMessageKey key = entry.key();
        return new CanStudySignalDefinitionView(
                false,
                key,
                entry.canIdHex(),
                "Ch" + key.channelId(),
                entry.dbcMessageName(),
                entry.definitionDisplayName(),
                entry.definitionSourceId().orElse(null),
                entry.signalCount(),
                "",
                signal
        );
    }

    public boolean groupHeader() { return groupHeader; }
    public StudyMessageKey studyKey() { return studyKey; }
    public ProjectSourceId sourceId() { return studyKey.sourceId(); }
    public SourceChannelKey channelKey() { return studyKey.channelKey(); }
    public String canIdHex() { return canIdHex; }
    public String channelLabel() { return channelLabel; }
    public String dbcMessageName() { return dbcMessageName; }
    public String dbcMessageDisplay() { return dbcMessageName.isBlank() ? "—" : dbcMessageName; }
    public String definitionDisplayName() { return definitionDisplayName; }
    public Optional<DefinitionSourceId> definitionSourceId() { return Optional.ofNullable(definitionSourceId); }
    public int groupSignalCount() { return groupSignalCount; }
    public String headerLabel() { return headerLabel; }
    public Optional<DbcSignalDefinitionView> signal() { return Optional.ofNullable(signal); }
    public String signalName() { return signal == null ? "" : signal.name(); }
    public String startDisplay() { return signal == null ? "" : Integer.toString(signal.startBit()); }
    public String lenDisplay() { return signal == null ? "" : Integer.toString(signal.bitLength()); }
    public String unitDisplay() { return signal == null ? "" : signal.unitDisplay(); }

    public boolean matchesSearch(String queryLower) {
        if (queryLower == null || queryLower.isBlank()) {
            return true;
        }
        String q = queryLower.toLowerCase(Locale.ROOT);
        if (canIdHex.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (dbcMessageName.toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        if (signal != null && signal.name().toLowerCase(Locale.ROOT).contains(q)) {
            return true;
        }
        return channelLabel.toLowerCase(Locale.ROOT).contains(q);
    }
}

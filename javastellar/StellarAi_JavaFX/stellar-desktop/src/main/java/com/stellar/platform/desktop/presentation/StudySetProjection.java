package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.SourceChannelKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a cheap Study Set signal projection from stored definition metadata.
 * No parser / rematch / sample decode.
 */
public final class StudySetProjection {

    public record Summary(
            int messageCount,
            int signalCount,
            int channelCount,
            boolean singleScope,
            String scopeLabel
    ) {
        /** Compact one-line Study header text. */
        public String compactLine() {
            if (messageCount == 0) {
                return "";
            }
            String channelPart = singleScope
                    ? scopeLabel
                    : channelCount + " Channels";
            return messageCount + " CAN Messages \u00b7 "
                    + signalCount + " Signal Definitions \u00b7 "
                    + channelPart;
        }
    }

    private StudySetProjection() {
    }

    public static Summary summarize(List<StudyMessageEntry> entries) {
        List<StudyMessageEntry> list = entries == null ? List.of() : entries;
        int signals = 0;
        Set<SourceChannelKey> scopes = new LinkedHashSet<>();
        for (StudyMessageEntry e : list) {
            signals += e.signalCount();
            scopes.add(e.key().channelKey());
        }
        String scopeLabel = "";
        if (scopes.size() == 1) {
            SourceChannelKey key = scopes.iterator().next();
            scopeLabel = "Ch" + key.channelId();
        } else if (scopes.size() > 1) {
            scopeLabel = scopes.size() + " Channels";
        }
        return new Summary(list.size(), signals, scopes.size(), scopes.size() <= 1, scopeLabel);
    }

    /** Flat signal rows for the Study inspector table (Ch | CAN ID | Signal | …). */
    public static List<CanStudySignalDefinitionView> buildFlat(List<StudyMessageEntry> entries) {
        return buildFlat(entries, "");
    }

    public static List<CanStudySignalDefinitionView> buildFlat(
            List<StudyMessageEntry> entries,
            String searchText
    ) {
        List<StudyMessageEntry> list = entries == null ? List.of() : entries;
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        List<CanStudySignalDefinitionView> out = new ArrayList<>();
        for (StudyMessageEntry entry : list) {
            Objects.requireNonNull(entry, "entry");
            for (DbcSignalDefinitionView signal : entry.signalDefinitions()) {
                CanStudySignalDefinitionView row = CanStudySignalDefinitionView.signalRow(entry, signal);
                if (q.isEmpty() || row.matchesSearch(q)) {
                    out.add(row);
                }
            }
        }
        return List.copyOf(out);
    }

    public static List<CanStudySignalDefinitionView> buildGrouped(List<StudyMessageEntry> entries) {
        return buildGrouped(entries, "");
    }

    public static List<CanStudySignalDefinitionView> buildGrouped(
            List<StudyMessageEntry> entries,
            String searchText
    ) {
        List<StudyMessageEntry> list = entries == null ? List.of() : entries;
        boolean showChannel = !summarize(list).singleScope();
        String q = searchText == null ? "" : searchText.trim().toLowerCase(Locale.ROOT);
        List<CanStudySignalDefinitionView> out = new ArrayList<>();
        for (StudyMessageEntry entry : list) {
            Objects.requireNonNull(entry, "entry");
            List<CanStudySignalDefinitionView> signalRows = new ArrayList<>();
            for (DbcSignalDefinitionView signal : entry.signalDefinitions()) {
                CanStudySignalDefinitionView row = CanStudySignalDefinitionView.signalRow(entry, signal);
                if (q.isEmpty() || row.matchesSearch(q)) {
                    signalRows.add(row);
                }
            }
            CanStudySignalDefinitionView header = CanStudySignalDefinitionView.groupHeader(entry, showChannel);
            boolean headerMatches = q.isEmpty() || header.matchesSearch(q);
            if (signalRows.isEmpty() && !headerMatches) {
                continue;
            }
            out.add(header);
            out.addAll(signalRows);
        }
        return List.copyOf(out);
    }
}

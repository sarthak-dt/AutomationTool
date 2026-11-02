package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Signals KPI copy for definitions that processing actually applied.
 * Imported-but-unused DBC files and native-only jobs are not applied definitions.
 */
public final class AppliedDbcPresentation {

    static final String NATIVE_IDENTITY = "_none_";
    public static final String NATIVE_ONLY = "Native only";

    public record Summary(
            String caption,
            String value,
            String detail,
            int consideredJobs,
            int channelCount,
            int measurementCount,
            boolean includesNativeOnly,
            boolean jobProvenance
    ) {
        public Summary {
            caption = caption == null ? "APPLIED DBC" : caption;
            value = value == null ? "" : value;
            detail = detail == null ? "" : detail;
            consideredJobs = Math.max(0, consideredJobs);
            channelCount = Math.max(0, channelCount);
            measurementCount = Math.max(0, measurementCount);
        }

        public String channelsLabel() {
            if (channelCount == 1) {
                return "1 Channel";
            }
            return channelCount + " Channels";
        }
    }

    private AppliedDbcPresentation() {
    }

    /**
     * @param measurementId blank for every successful job; otherwise one {@code projectSourceId}
     */
    public static Summary fromResults(List<IndependentJobResult> results, String measurementId) {
        String scope = measurementId == null ? "" : measurementId.trim();
        LinkedHashMap<String, String> namesById = new LinkedHashMap<>();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        int jobs = 0;
        boolean nativeOnly = false;
        if (results != null) {
            for (IndependentJobResult result : results) {
                if (result == null || !result.successful()) {
                    continue;
                }
                if (!scope.isEmpty() && !scope.equals(result.projectSourceId())) {
                    continue;
                }
                jobs++;
                if (result.projectSourceId() != null && !result.projectSourceId().isBlank()) {
                    sources.add(result.projectSourceId());
                }
                String id = result.definitionSourceId().orElse("");
                if (!isAppliedIdentity(id)) {
                    nativeOnly = true;
                    continue;
                }
                String name = result.definitionDisplayName();
                if (!isAppliedName(name)) {
                    name = id;
                }
                namesById.putIfAbsent(id, name.trim());
            }
        }
        return summarize(namesById, jobs, jobs, sources.size(), nativeOnly, true);
    }

    /**
     * Catalog fallback when job rows are not in memory. Uses the definition display name
     * stored with each decoded signal, which processing wrote from the applied definition.
     */
    public static Summary fromEntries(List<SignalCatalogEntry> entries) {
        LinkedHashMap<String, String> namesById = new LinkedHashMap<>();
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        boolean nativeOnly = false;
        int rows = 0;
        if (entries != null) {
            for (SignalCatalogEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                rows++;
                channels.add(entry.channel() + ":" + (entry.key() == null ? "" : entry.key().sourceFile()));
                String source = entry.key() == null ? "" : entry.key().sourceFile();
                if (source != null && !source.isBlank() && !"PROJECT".equalsIgnoreCase(source)) {
                    sources.add(source);
                }
                if (entry.sourceType() == SignalSourceType.MF4_NATIVE) {
                    nativeOnly = true;
                    continue;
                }
                String name = entry.sourceDatabase();
                if (!isAppliedName(name) && entry.key() != null) {
                    name = entry.key().sourceDatabase();
                }
                if (!isAppliedName(name)) {
                    if (entry.sourceType() != SignalSourceType.DBC_CAN) {
                        nativeOnly = true;
                    }
                    continue;
                }
                namesById.putIfAbsent(name.trim(), name.trim());
            }
        }
        int measurements = sources.isEmpty() && rows > 0 ? 1 : sources.size();
        return summarize(namesById, rows, channels.size(), measurements, nativeOnly, false);
    }

    /** Last resort when only the processing-context summary string is available. */
    public static Summary fromContextLabel(String dbcFile) {
        String text = dbcFile == null ? "" : dbcFile.trim();
        if (text.isEmpty() || "—".equals(text)) {
            return new Summary("DBC", "—", "", 0, 0, 0, false, false);
        }
        if (!isAppliedName(text)) {
            return new Summary("APPLIED DBC", NATIVE_ONLY, NATIVE_ONLY, 0, 0, 0, true, false);
        }
        if (text.endsWith(" DBCs")) {
            String count = text.substring(0, text.length() - " DBCs".length()).trim();
            return new Summary("APPLIED DBCS", count, text, 0, 0, 0, false, false);
        }
        return new Summary("APPLIED DBC", text, text, 0, 0, 0, false, false);
    }

    private static Summary summarize(
            Map<String, String> namesById,
            int considered,
            int channels,
            int measurements,
            boolean includesNativeOnly,
            boolean jobProvenance
    ) {
        if (namesById.isEmpty()) {
            String value = considered == 0 ? "—" : NATIVE_ONLY;
            String caption = considered == 0 ? "DBC" : "APPLIED DBC";
            return new Summary(caption, value, value, considered, channels, measurements,
                    includesNativeOnly, jobProvenance);
        }
        String nativeNote = includesNativeOnly
                ? "\nNative-only channels did not use a DBC."
                : "";
        if (namesById.size() == 1) {
            String name = namesById.values().iterator().next();
            return new Summary(
                    "APPLIED DBC",
                    name,
                    name + nativeNote,
                    considered,
                    channels,
                    measurements,
                    includesNativeOnly,
                    jobProvenance);
        }
        String detail = String.join("\n", namesById.values()) + nativeNote;
        return new Summary(
                "APPLIED DBCS",
                namesById.size() + " DBCs applied",
                detail,
                considered,
                channels,
                measurements,
                includesNativeOnly,
                jobProvenance);
    }

    static boolean isAppliedIdentity(String id) {
        return id != null && !id.isBlank() && !NATIVE_IDENTITY.equals(id);
    }

    static boolean isAppliedName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String text = name.trim();
        if (NATIVE_IDENTITY.equals(text) || "none".equalsIgnoreCase(text) || NATIVE_ONLY.equalsIgnoreCase(text)) {
            return false;
        }
        return !text.toLowerCase(Locale.ROOT).endsWith(" dbcs");
    }
}

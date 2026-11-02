package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * User-facing processing warnings derived from persisted job diagnostics. Nothing here is
 * invented: every item traces to a job status, diagnostic, unmatched-frame count or technical
 * detail that already exists on {@link IndependentJobResult}.
 */
public final class ProcessingWarningReport {

    public enum Category {
        DECODE("Decode warnings"),
        UNMATCHED_CAN("Unmatched CAN IDs"),
        RECOVERED("Recovered data"),
        OUT_OF_RANGE("Out-of-range values"),
        UNSUPPORTED_METADATA("Unsupported metadata"),
        PROCESSING("Processing warnings");

        private final String title;

        Category(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    public record Item(
            Category category,
            String summary,
            String source,
            String signalOrMessage,
            long count,
            boolean analysisCanContinue,
            String technicalDetail
    ) {
        public Item {
            category = Objects.requireNonNull(category, "category");
            summary = summary == null ? "" : summary;
            source = source == null ? "" : source;
            signalOrMessage = signalOrMessage == null ? "" : signalOrMessage;
            count = Math.max(0L, count);
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }
    }

    private static final ProcessingWarningReport EMPTY = new ProcessingWarningReport(List.of());

    private final List<Item> items;

    public ProcessingWarningReport(List<Item> items) {
        this.items = List.copyOf(items == null ? List.of() : items);
    }

    public static ProcessingWarningReport empty() {
        return EMPTY;
    }

    public List<Item> items() {
        return items;
    }

    public int count() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Map<Category, List<Item>> grouped() {
        Map<Category, List<Item>> grouped = new EnumMap<>(Category.class);
        for (Item item : items) {
            grouped.computeIfAbsent(item.category(), key -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    /**
     * @param sourceName resolves a processing source id to the measurement filename when known
     */
    public static ProcessingWarningReport fromJobs(
            Iterable<IndependentJobResult> jobs,
            Function<String, String> sourceName
    ) {
        if (jobs == null) {
            return EMPTY;
        }
        Function<String, String> names = sourceName == null ? id -> id == null ? "" : id : sourceName;
        List<Item> items = new ArrayList<>();
        for (IndependentJobResult job : jobs) {
            if (job == null) {
                continue;
            }
            int before = items.size();
            collectFromJob(job, names, items);
            if (items.size() == before
                    && job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                items.add(item(
                        Category.PROCESSING,
                        "Processing completed with warnings. Analysis can continue.",
                        names.apply(job.projectSourceId()),
                        channelLabel(job),
                        0,
                        true,
                        job.technicalDetail()));
            }
        }
        return items.isEmpty() ? EMPTY : new ProcessingWarningReport(items);
    }

    private static void collectFromJob(
            IndependentJobResult job,
            Function<String, String> names,
            List<Item> items
    ) {
        String source = names.apply(job.projectSourceId());
        String channel = channelLabel(job);
        if (job.dbcUnmatchedFrames() > 0) {
            items.add(item(
                    Category.UNMATCHED_CAN,
                    job.dbcUnmatchedFrames() == 1
                            ? "1 CAN frame did not match the assigned DBC. Matched signals remain available."
                            : job.dbcUnmatchedFrames() + " CAN frames did not match the assigned DBC. Matched signals remain available.",
                    source,
                    channel,
                    job.dbcUnmatchedFrames(),
                    true,
                    unmatchedTechnical(job)));
        }
        for (ProcessingPlanDiagnostic diagnostic : job.diagnostics()) {
            if (diagnostic == null) {
                continue;
            }
            String blob = blob(diagnostic, job.technicalDetail());
            Category category = classify(diagnostic.code(), blob);
            if (category == Category.UNMATCHED_CAN && job.dbcUnmatchedFrames() > 0) {
                continue;
            }
            items.add(item(
                    category,
                    summaryFor(category, blob, diagnostic),
                    source,
                    messageLabel(diagnostic, channel),
                    0,
                    true,
                    technicalOf(diagnostic, job.technicalDetail())));
        }
        if (job.diagnostics().isEmpty() && job.dbcUnmatchedFrames() == 0) {
            String technical = job.technicalDetail();
            if (technical != null && !technical.isBlank()) {
                Category category = classify(null, technical);
                items.add(item(
                        category,
                        summaryFor(category, technical, null),
                        source,
                        channel,
                        0,
                        true,
                        technical));
            }
        }
    }

    static Category classify(ProcessingPlanDiagnosticCode code, String blob) {
        String text = blob == null ? "" : blob.toUpperCase(Locale.ROOT);
        if (text.contains("RECOVERY") || text.contains("RECOVERED")) {
            return Category.RECOVERED;
        }
        if (text.contains("UNMATCHED_CAN") || text.contains("UNMATCHED CAN") || text.contains("NO MATCHING CAN")) {
            return Category.UNMATCHED_CAN;
        }
        if (text.contains("OUT_OF_RANGE") || text.contains("OUT-OF-RANGE") || text.contains("OUT OF RANGE")) {
            return Category.OUT_OF_RANGE;
        }
        if (text.contains("UNSUPPORTED") || text.contains("ZERO_DECLARED") || text.contains("NO_VALID_RECORDS")
                || text.contains("METADATA")) {
            return Category.UNSUPPORTED_METADATA;
        }
        if (code == ProcessingPlanDiagnosticCode.UNCONFIRMED_DEFINITION
                || code == ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION
                || code == ProcessingPlanDiagnosticCode.AMBIGUOUS_DEFINITION
                || code == ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE
                || text.contains("DBC/") || text.contains("DECODE")) {
            return Category.DECODE;
        }
        return Category.PROCESSING;
    }

    private static String summaryFor(Category category, String blob, ProcessingPlanDiagnostic diagnostic) {
        return switch (category) {
            case RECOVERED -> "This measurement needed recovery before it could be decoded. Analysis can continue.";
            case UNMATCHED_CAN -> "CAN frames did not match the assigned DBC. Matched signals remain available.";
            case OUT_OF_RANGE -> "Physical values were outside the expected range. Analysis can continue.";
            case UNSUPPORTED_METADATA -> "Some measurement metadata could not be applied. Analysis can continue.";
            case DECODE -> decodeSummary(diagnostic);
            case PROCESSING -> processingSummary(diagnostic, blob);
        };
    }

    private static String decodeSummary(ProcessingPlanDiagnostic diagnostic) {
        if (diagnostic == null) {
            return "Decode completed with warnings. Analysis can continue.";
        }
        return switch (diagnostic.code()) {
            case UNCONFIRMED_DEFINITION -> "A decode definition was not confirmed for this channel. Analysis can continue with the applied mapping.";
            case NO_EFFECTIVE_DEFINITION -> "No effective decode definition was available for this channel.";
            case AMBIGUOUS_DEFINITION -> "More than one decode definition could apply to this channel.";
            case DEFINITION_NOT_AVAILABLE -> "The assigned decode definition was not available.";
            default -> "Decode completed with warnings. Analysis can continue.";
        };
    }

    private static String processingSummary(ProcessingPlanDiagnostic diagnostic, String blob) {
        if (diagnostic != null && diagnostic.code() == ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH) {
            return "Recorded frame counts differ from the source inventory. Analysis can continue.";
        }
        if (diagnostic != null && diagnostic.code() == ProcessingPlanDiagnosticCode.SOURCE_NOT_READY) {
            return "The source was not fully ready during processing. Review the measurement if results look incomplete.";
        }
        if (blob != null && !blob.isBlank() && blob.length() < 160 && !looksLikeCode(blob)) {
            return blob;
        }
        return "Processing completed with warnings. Analysis can continue.";
    }

    private static boolean looksLikeCode(String blob) {
        String upper = blob.toUpperCase(Locale.ROOT);
        return upper.contains("/") || upper.contains("_") && upper.equals(blob);
    }

    private static Item item(
            Category category,
            String summary,
            String source,
            String signalOrMessage,
            long count,
            boolean analysisCanContinue,
            String technicalDetail
    ) {
        return new Item(category, summary, source, signalOrMessage, count, analysisCanContinue, technicalDetail);
    }

    private static String channelLabel(IndependentJobResult job) {
        String definition = job.definitionDisplayName();
        if (definition != null && !definition.isBlank()) {
            return definition;
        }
        return "Channel " + job.channelId();
    }

    private static String messageLabel(ProcessingPlanDiagnostic diagnostic, String fallback) {
        if (diagnostic != null && diagnostic.channel().isPresent()) {
            return fallback + " · channel " + diagnostic.channel().get().channelId();
        }
        return fallback;
    }

    private static String blob(ProcessingPlanDiagnostic diagnostic, String technical) {
        String detail = diagnostic.detail() == null ? "" : diagnostic.detail();
        String tech = technical == null ? "" : technical;
        return (diagnostic.code() + " " + detail + " " + tech).trim();
    }

    private static String technicalOf(ProcessingPlanDiagnostic diagnostic, String technical) {
        String detail = diagnostic == null ? "" : diagnostic.detail();
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return technical == null ? "" : technical;
    }

    private static String unmatchedTechnical(IndependentJobResult job) {
        if (job.technicalDetail() != null && !job.technicalDetail().isBlank()) {
            return job.technicalDetail();
        }
        return "unmatchedFrames=" + job.dbcUnmatchedFrames() + " matchedFrames=" + job.dbcMatchedFrames();
    }
}

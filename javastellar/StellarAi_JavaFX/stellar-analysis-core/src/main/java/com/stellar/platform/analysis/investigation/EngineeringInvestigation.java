package com.stellar.platform.analysis.investigation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * First-class local engineering investigation. Holds references and notes only —
 * never waveform samples.
 */
public final class EngineeringInvestigation {

    private final String investigationId;
    private final String projectId;
    private final String name;
    private final String description;
    private final InvestigationStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Long rangeStartNanos;
    private final Long rangeEndNanos;
    private final String graphTracesBlob;
    private final String layoutMode;
    private final Long viewportStartNanos;
    private final Long viewportEndNanos;
    private final Long cursorANanos;
    private final Long cursorBNanos;
    private final String focusKeyBlob;
    private final String conclusion;
    private final InvestigationReportConfig reportConfig;
    private final List<String> signalKeyBlobs;
    private final List<EngineeringEvidenceItem> evidence;
    private final List<EngineeringObservation> observations;

    private EngineeringInvestigation(Builder b) {
        this.investigationId = b.investigationId == null || b.investigationId.isBlank()
                ? UUID.randomUUID().toString() : b.investigationId;
        this.projectId = b.projectId == null ? "" : b.projectId;
        this.name = b.name == null || b.name.isBlank() ? "Untitled investigation" : b.name;
        this.description = b.description == null ? "" : b.description;
        this.status = b.status == null ? InvestigationStatus.DRAFT : b.status;
        this.createdAt = b.createdAt == null ? Instant.now() : b.createdAt;
        this.updatedAt = b.updatedAt == null ? createdAt : b.updatedAt;
        this.rangeStartNanos = b.rangeStartNanos;
        this.rangeEndNanos = b.rangeEndNanos;
        this.graphTracesBlob = b.graphTracesBlob == null ? "" : b.graphTracesBlob;
        this.layoutMode = b.layoutMode == null || b.layoutMode.isBlank() ? "STACKED" : b.layoutMode;
        this.viewportStartNanos = b.viewportStartNanos;
        this.viewportEndNanos = b.viewportEndNanos;
        this.cursorANanos = b.cursorANanos;
        this.cursorBNanos = b.cursorBNanos;
        this.focusKeyBlob = b.focusKeyBlob == null ? "" : b.focusKeyBlob;
        this.conclusion = b.conclusion == null ? "" : b.conclusion;
        this.reportConfig = b.reportConfig == null ? InvestigationReportConfig.allRelevant() : b.reportConfig;
        this.signalKeyBlobs = List.copyOf(b.signalKeyBlobs);
        this.evidence = List.copyOf(b.evidence);
        this.observations = List.copyOf(b.observations);
    }

    public static Builder builder(String projectId) {
        return new Builder(projectId);
    }

    public String investigationId() { return investigationId; }
    public String projectId() { return projectId; }
    public String name() { return name; }
    public String description() { return description; }
    public InvestigationStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Long rangeStartNanos() { return rangeStartNanos; }
    public Long rangeEndNanos() { return rangeEndNanos; }
    public String graphTracesBlob() { return graphTracesBlob; }
    public String layoutMode() { return layoutMode; }
    public Long viewportStartNanos() { return viewportStartNanos; }
    public Long viewportEndNanos() { return viewportEndNanos; }
    public Long cursorANanos() { return cursorANanos; }
    public Long cursorBNanos() { return cursorBNanos; }
    public String focusKeyBlob() { return focusKeyBlob; }
    public String conclusion() { return conclusion; }
    public InvestigationReportConfig reportConfig() { return reportConfig; }
    public List<String> signalKeyBlobs() { return signalKeyBlobs; }
    public List<EngineeringEvidenceItem> evidence() { return evidence; }
    public List<EngineeringObservation> observations() { return observations; }

    public InvestigationHealth health() {
        return InvestigationValidity.health(evidence);
    }

    public Builder toBuilder() {
        Builder b = new Builder(projectId);
        b.investigationId = investigationId;
        b.name = name;
        b.description = description;
        b.status = status;
        b.createdAt = createdAt;
        b.updatedAt = updatedAt;
        b.rangeStartNanos = rangeStartNanos;
        b.rangeEndNanos = rangeEndNanos;
        b.graphTracesBlob = graphTracesBlob;
        b.layoutMode = layoutMode;
        b.viewportStartNanos = viewportStartNanos;
        b.viewportEndNanos = viewportEndNanos;
        b.cursorANanos = cursorANanos;
        b.cursorBNanos = cursorBNanos;
        b.focusKeyBlob = focusKeyBlob;
        b.conclusion = conclusion;
        b.reportConfig = reportConfig;
        b.signalKeyBlobs.addAll(signalKeyBlobs);
        b.evidence.addAll(evidence);
        b.observations.addAll(observations);
        return b;
    }

    public static final class Builder {
        private String investigationId;
        private final String projectId;
        private String name = "";
        private String description = "";
        private InvestigationStatus status = InvestigationStatus.DRAFT;
        private Instant createdAt;
        private Instant updatedAt;
        private Long rangeStartNanos;
        private Long rangeEndNanos;
        private String graphTracesBlob = "";
        private String layoutMode = "STACKED";
        private Long viewportStartNanos;
        private Long viewportEndNanos;
        private Long cursorANanos;
        private Long cursorBNanos;
        private String focusKeyBlob = "";
        private String conclusion = "";
        private InvestigationReportConfig reportConfig = InvestigationReportConfig.allRelevant();
        private final List<String> signalKeyBlobs = new ArrayList<>();
        private final List<EngineeringEvidenceItem> evidence = new ArrayList<>();
        private final List<EngineeringObservation> observations = new ArrayList<>();

        private Builder(String projectId) {
            this.projectId = projectId;
        }

        public Builder investigationId(String v) { this.investigationId = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder status(InvestigationStatus v) { this.status = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder range(Long start, Long end) {
            this.rangeStartNanos = start;
            this.rangeEndNanos = end;
            return this;
        }
        public Builder graphState(String traces, String layout, Long vStart, Long vEnd,
                                  Long cursorA, Long cursorB, String focus) {
            this.graphTracesBlob = traces;
            this.layoutMode = layout;
            this.viewportStartNanos = vStart;
            this.viewportEndNanos = vEnd;
            this.cursorANanos = cursorA;
            this.cursorBNanos = cursorB;
            this.focusKeyBlob = focus;
            return this;
        }
        public Builder conclusion(String v) { this.conclusion = v; return this; }
        public Builder reportConfig(InvestigationReportConfig v) { this.reportConfig = v; return this; }
        public Builder signalKeyBlobs(List<String> v) {
            this.signalKeyBlobs.clear();
            if (v != null) {
                this.signalKeyBlobs.addAll(v);
            }
            return this;
        }
        public Builder evidence(List<EngineeringEvidenceItem> v) {
            this.evidence.clear();
            if (v != null) {
                this.evidence.addAll(v);
            }
            return this;
        }
        public Builder observations(List<EngineeringObservation> v) {
            this.observations.clear();
            if (v != null) {
                this.observations.addAll(v);
            }
            return this;
        }

        public EngineeringInvestigation build() {
            return new EngineeringInvestigation(this);
        }
    }
}

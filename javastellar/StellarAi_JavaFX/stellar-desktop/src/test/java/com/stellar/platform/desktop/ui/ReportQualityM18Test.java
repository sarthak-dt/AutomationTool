package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.analysis.investigation.report.EvidenceQuality;
import com.stellar.platform.analysis.investigation.report.QualityFinding;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ReportQualityM18Test {

    private static final Path MF4 = Path.of(
            "C:\\Users\\DT - User\\Downloads\\Mahima_Time_Issue\\tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:\\Users\\DT - User\\Downloads\\Mahima_Time_Issue\\TEMP DBC 1 (1).dbc");

    @TempDir
    Path temp;

    @Test
    @Timeout(180)
    void sierraFindingsUseOriginalSamplesAndMatchInHtmlAndPdf() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        long started = System.nanoTime();
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        var processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        SignalSeriesStore store = processed.catalogStore();
        List<EvidenceQuality.SampleObservation> observations = new ArrayList<>();
        long examined = 0;
        int signals = 0;
        for (SignalCatalogEntry entry : store.snapshot().entries()) {
            if (entry == null || signals >= 3) {
                continue;
            }
            String name = entry.signalName();
            if (!name.startsWith("CH01") && !name.startsWith("CH02") && !name.equals("LATITUDE")) {
                continue;
            }
            observations.add(read(store, entry));
            examined += observations.getLast().examined();
            signals++;
        }
        long scanMs = (System.nanoTime() - started) / 1_000_000L;
        List<QualityFinding> findings = EvidenceQuality.fromSamples(observations);
        assertFalse(findings.isEmpty());
        QualityFinding ch01 = findings.stream()
                .filter(finding -> finding.signal().startsWith("CH01") && finding.type().equals("sample-inventory"))
                .findFirst().orElseThrow();
        assertTrue(ch01.measured().contains("first="));
        assertFalse(ch01.time().contains("."));
        assertFalse(ch01.explanation().toLowerCase().contains("defect"));
        assertFalse(ch01.limitations().toLowerCase().contains("failed sensor"));
        long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        EngineeringReportModel model = EngineeringReportModel.builder()
                .template(EngineeringReportTemplate.ENGINEERING_V2)
                .sections(List.of(EngineeringReportSection.values()))
                .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                .header(new EngineeringReportModel.Header(
                        "Tata Sierra evidence quality", "Tata Sierra", "M18",
                        Instant.parse("2026-09-21T13:30:54Z"), "1.0.3", "QA"))
                .project(new EngineeringReportModel.ProjectSection(
                        "Tata Sierra", 1, 1, 1, signals, "COMPLETED", 0))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        "M18", "Original MF4 samples. QA fixtures are not included.",
                        InvestigationStatus.DRAFT, "", "", 1))
                .sources(List.of(new EngineeringReportModel.SourceRow(
                        MF4.getFileName().toString(), "MF4", "", "", "COMPLETED")))
                .definitions(List.of(new EngineeringReportModel.DefinitionRow(
                        DBC.getFileName().toString(), "DBC", "Attached")))
                .findings(findings)
                .build();
        Path htmlPath = Path.of("target", "m18-sierra-report.html");
        Path pdfPath = Path.of("target", "m18-sierra-report.pdf");
        Files.createDirectories(htmlPath.getParent());
        long reportStart = System.nanoTime();
        String html = EngineeringHtmlReportRenderer.render(model);
        Files.writeString(htmlPath, html);
        EngineeringPdfReportRenderer.write(model, pdfPath, temp);
        long reportMs = (System.nanoTime() - reportStart) / 1_000_000L;
        String pdf;
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            pdf = new PDFTextStripper().getText(document);
        }
        String flatHtml = html.replaceAll("\\s+", "");
        String flatPdf = pdf.replaceAll("\\s+", "");
        for (QualityFinding finding : findings) {
            assertTrue(flatHtml.contains(finding.id()), finding.id());
            assertTrue(flatPdf.contains(finding.id()), finding.id());
            assertTrue(flatHtml.contains(finding.status().label()));
            assertTrue(flatPdf.contains(finding.status().label()));
            if (!finding.measured().isBlank()) {
                String measured = finding.measured().replaceAll("\\s+", "");
                assertTrue(flatHtml.contains(measured), finding.measured());
                assertTrue(flatPdf.contains(measured), finding.measured());
            }
        }
        System.out.println("M18 scanMs=" + scanMs
                + " signals=" + signals
                + " examined=" + examined
                + " findings=" + findings.size()
                + " reportMs=" + reportMs
                + " heapBytes=" + heap
                + " htmlBytes=" + Files.size(htmlPath)
                + " pdfBytes=" + Files.size(pdfPath));
    }

    private static EvidenceQuality.SampleObservation read(SignalSeriesStore store, SignalCatalogEntry entry) {
        List<SignalSamplePoint> samples = new ArrayList<>();
        int offset = 0;
        while (samples.size() < 250_000) {
            List<SignalSamplePoint> page = store.querySamples(entry.key(), offset, 4_096);
            if (page == null || page.isEmpty()) {
                break;
            }
            samples.addAll(page);
            if (page.size() < 4_096) {
                break;
            }
            offset += page.size();
        }
        long[] times = new long[samples.size()];
        double[] values = new double[samples.size()];
        boolean[] usable = new boolean[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            SignalSamplePoint sample = samples.get(i);
            times[i] = sample.timestampNanos();
            values[i] = sample.physicalValue();
            usable[i] = sample.usableMeasurement();
        }
        return EvidenceQuality.observe(
                entry.signalName(), entry.unit(), entry.sourceFile(),
                Integer.toString(entry.channel()), entry.sourceDatabase(),
                times, values, usable);
    }

    private static int count(String html, String token) {
        int n = 0;
        int at = 0;
        while ((at = html.indexOf(token, at)) >= 0) {
            n++;
            at += token.length();
        }
        return n;
    }

    private static int countOther(String html, List<QualityFinding> findings) {
        int n = 0;
        for (QualityFinding finding : findings) {
            if (!finding.type().equals("sample-inventory") && html.contains(finding.type())) {
                n++;
            }
        }
        return n;
    }
}

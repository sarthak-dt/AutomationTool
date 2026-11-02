package com.stellar.platform.analysis.investigation.kpi;

import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiSeries.Sample;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringKpiCalculatorTest {

    @TempDir
    Path temp;

    @Test
    void constantSpeedMatchesRectangularHoldAndUnitConversion() {
        EngineeringKpiConfig config = EngineeringKpiConfig.parse("""
                gap=2
                move=1
                speed=Veh
                """);
        List<EngineeringKpiResult> kmh = EngineeringKpiCalculator.calculate(config, List.of(
                speed("Veh", "km/h", "a.mf4", constant(36, 0, 10))));
        EngineeringKpiResult distance = one(kmh, "vehicle.distance.speed");
        assertEquals(36.0 / 3.6 * 10.0 / 1000.0, distance.numericValue(), 1e-9);
        assertEquals("km", distance.unit());
        assertEquals(ReportFactKind.CALCULATED, distance.provenance());
        assertEquals(10.0, one(kmh, "vehicle.speed.movingDuration").numericValue(), 1e-9);
        assertEquals(36.0, one(kmh, "vehicle.speed.meanMoving").numericValue(), 1e-9);
        assertEquals(36.0, one(kmh, "vehicle.speed.max").numericValue(), 1e-9);

        List<EngineeringKpiResult> ms = EngineeringKpiCalculator.calculate(config, List.of(
                speed("Veh", "m/s", "a.mf4", constant(10, 0, 10))));
        assertEquals(0.1, one(ms, "vehicle.distance.speed").numericValue(), 1e-9);

        List<EngineeringKpiResult> mph = EngineeringKpiCalculator.calculate(config, List.of(
                speed("Veh", "mph", "a.mf4", constant(10, 0, 10))));
        assertEquals(10.0 * 0.44704 * 10.0 / 1000.0, one(mph, "vehicle.distance.speed").numericValue(), 1e-9);
    }

    @Test
    void gapsDuplicatesMissingDataAndBoundariesDoNotInventValues() {
        EngineeringKpiConfig wide = EngineeringKpiConfig.parse("""
                gap=2
                move=10
                rpmRun=500
                rpmAbove=4000
                wot=90
                active=1
                speed=Veh
                rpm=Rpm
                accel=Pedal
                abs=Abs
                """);
        EngineeringKpiConfig narrow = EngineeringKpiConfig.parse("""
                gap=1
                move=10
                speed=Veh
                """);
        List<EngineeringKpiResult> gapped = EngineeringKpiCalculator.calculate(narrow, List.of(
                speed("Veh", "km/h", "a.mf4", List.of(sample(0, 36, true), sample(5, 36, true)))));
        assertEquals(0.0, one(gapped, "vehicle.distance.speed").numericValue(), 1e-9);
        assertTrue(one(gapped, "vehicle.distance.speed").notes().contains("Gaps excluded: 1"));
        assertEquals(36.0, one(gapped, "vehicle.speed.max").numericValue(), 1e-9);

        List<EngineeringKpiResult> duplicates = EngineeringKpiCalculator.calculate(wide, List.of(
                speed("Veh", "km/h", "a.mf4", List.of(
                        sample(0, 0, true), sample(0, 36, true), sample(1, 36, true)))));
        assertEquals(36.0 / 3.6 * 1.0 / 1000.0, one(duplicates, "vehicle.distance.speed").numericValue(), 1e-9);

        List<EngineeringKpiResult> missing = EngineeringKpiCalculator.calculate(wide, List.of(
                speed("Veh", "km/h", "a.mf4", List.of(
                        sample(0, 36, true), sample(1, Double.NaN, false), sample(2, 36, true)))));
        assertEquals(36.0 / 3.6 * 1.0 / 1000.0, one(missing, "vehicle.distance.speed").numericValue(), 1e-9);
        assertTrue(one(missing, "vehicle.distance.speed").notes().contains("Invalid breaks: 1"));
        assertEquals(36.0, one(missing, "vehicle.speed.max").numericValue(), 1e-9);
        assertFalse(one(missing, "vehicle.speed.max").valueText().equals("0"));

        List<EngineeringKpiResult> boundary = EngineeringKpiCalculator.calculate(wide, List.of(
                speed("Veh", "km/h", "a.mf4", constant(10, 0, 4)),
                EngineeringKpiSeries.present("rpm", "Rpm", "rpm", "a.mf4", constant(4000, 0, 4)),
                EngineeringKpiSeries.present("accel", "Pedal", "%", "a.mf4", constant(90, 0, 4))));
        assertEquals(0.0, one(boundary, "vehicle.speed.movingDuration").numericValue(), 1e-9);
        assertNull(one(boundary, "vehicle.speed.meanMoving").numericValue());
        assertEquals(0.0, one(boundary, "engine.rpm.timeAbove").numericValue(), 1e-9);
        assertEquals(4.0, one(boundary, "accel.wot.time").numericValue(), 1e-9);

        List<EngineeringKpiResult> irregular = EngineeringKpiCalculator.calculate(wide, List.of(
                speed("Veh", "km/h", "a.mf4", List.of(
                        sample(0, 0, true), sample(0.5, 36, true), sample(2.0, 36, true)))));
        assertEquals(36.0 / 3.6 * 1.5 / 1000.0, one(irregular, "vehicle.distance.speed").numericValue(), 1e-9);
    }

    @Test
    void stateEdgesZeroDurationAndSeparateFilesStayTraceable() {
        EngineeringKpiConfig config = EngineeringKpiConfig.parse("""
                gap=2
                active=1
                abs=Abs
                speed=Veh
                thermo=CH01_FRONT_BUMPER_GRILL_AMBIENT
                """);
        List<EngineeringKpiResult> states = EngineeringKpiCalculator.calculate(config, List.of(
                EngineeringKpiSeries.present("abs", "Abs", "", "a.mf4", List.of(
                        sample(0, 0, true), sample(1, 1, true), sample(2, 1, true), sample(3, 0, true)))));
        assertEquals(2.0, one(states, "state.abs.duration").numericValue(), 1e-9);
        assertEquals(1.0, one(states, "state.abs.count").numericValue(), 1e-9);
        assertTrue(one(states, "state.abs.count").notes().contains("not a 100 ms"));

        List<EngineeringKpiResult> zero = EngineeringKpiCalculator.calculate(config, List.of(
                EngineeringKpiSeries.present("abs", "Abs", "", "a.mf4", List.of(sample(0, 1, true)))));
        assertEquals(0.0, one(zero, "state.abs.duration").numericValue(), 1e-9);
        assertEquals(1.0, one(zero, "state.abs.count").numericValue(), 1e-9);
        assertEquals(ReportFactKind.RULE_RESULT, one(zero, "state.abs.duration").provenance());

        List<EngineeringKpiResult> files = EngineeringKpiCalculator.calculate(config, List.of(
                speed("Veh", "km/h", "session-a.mf4", constant(36, 0, 10)),
                speed("Veh", "km/h", "session-b.mf4", constant(36, 0, 10)),
                EngineeringKpiSeries.present("thermo", "CH01_FRONT_BUMPER_GRILL_AMBIENT", "°C", "session-a.mf4",
                        List.of(sample(0, 20, true), sample(1, 39.8, true)))));
        List<EngineeringKpiResult> distances = files.stream()
                .filter(row -> row.id().equals("vehicle.distance.speed")).toList();
        assertEquals(2, distances.size());
        assertEquals("session-a.mf4", distances.get(0).sourceFile());
        assertEquals("session-b.mf4", distances.get(1).sourceFile());
        assertEquals(0.1, distances.get(0).numericValue(), 1e-6);
        assertEquals(0.1, distances.get(1).numericValue(), 1e-6);
        assertEquals(20.0, one(files, "thermal.tc.min").numericValue(), 1e-9);
        assertEquals(39.8, one(files, "thermal.tc.max").numericValue(), 1e-9);

        EngineeringKpiResult missingSignal = one(EngineeringKpiCalculator.calculate(
                EngineeringKpiConfig.parse("coolant=NotInCatalog"), List.of()), "thermal.coolant.max");
        assertEquals("Unavailable", missingSignal.valueText());
        assertNull(missingSignal.numericValue());
        assertTrue(missingSignal.notes().contains("not treated as zero"));
    }

    @Test
    void htmlAndPdfShareKpiFactsAndCanHideTheSection() throws Exception {
        EngineeringKpiConfig config = EngineeringKpiConfig.parse("""
                gap=2
                move=1
                speed=Veh<script>
                """);
        List<EngineeringKpiResult> rows = EngineeringKpiCalculator.calculate(config, List.of(
                speed("Veh<script>", "km/h", "a.mf4", constant(36, 0, 10))));
        String value = one(rows, "vehicle.distance.speed").valueText();
        EngineeringReportModel shown = model(List.of(EngineeringReportSection.ENGINEERING_KPIS), rows);
        String html = EngineeringHtmlReportRenderer.render(shown);
        Path pdf = temp.resolve("kpis.pdf");
        EngineeringPdfReportRenderer.write(shown, pdf, temp);
        String pdfText;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pdfText = new PDFTextStripper().getText(document);
            assertTrue(document.getNumberOfPages() >= 1);
        }
        assertTrue(html.contains(value));
        assertTrue(pdfText.contains(value));
        assertTrue(html.contains("km"));
        assertTrue(pdfText.contains("km"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertTrue(html.contains("v2-hold-1"));
        assertTrue(pdfText.contains("v2-hold-1"));
        assertFalse(html.contains("PASS"));
        assertFalse(pdfText.contains("PASS"));
        assertTrue(pdfText.contains("Engineering KPIs"));
        Path sampleDir = Files.isDirectory(Path.of("target"))
                ? Path.of("target")
                : Path.of("stellar-analysis-core", "target");
        Files.createDirectories(sampleDir);
        Files.writeString(sampleDir.resolve("engineering-kpi-sample.html"), html);
        Files.copy(pdf, sampleDir.resolve("engineering-kpi-sample.pdf"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        EngineeringReportModel hidden = model(List.of(EngineeringReportSection.CONCLUSIONS), rows);
        String hiddenHtml = EngineeringHtmlReportRenderer.render(hidden);
        assertFalse(hiddenHtml.contains("Engineering KPIs"));
        assertFalse(hiddenHtml.contains(value));
    }

    @Test
    void brakeUsesTheMappedSignalAndUnmappedRolesStayOut() {
        EngineeringKpiConfig config = EngineeringKpiConfig.parse("""
                gap=2
                active=1
                brake=BrakePressed
                """);
        List<EngineeringKpiResult> rows = EngineeringKpiCalculator.calculate(config, List.of(
                EngineeringKpiSeries.present("brake", "BrakePressed", "", "a.mf4", List.of(
                        sample(0, 0, true), sample(1, 1, true), sample(2, 1, true), sample(3, 0, true)))));
        assertEquals(2.0, one(rows, "state.brake.duration").numericValue(), 1e-9);
        assertEquals(1.0, one(rows, "state.brake.count").numericValue(), 1e-9);
        assertTrue(rows.stream().noneMatch(row -> row.id().startsWith("vehicle.")));
        assertTrue(rows.stream().noneMatch(row -> row.id().startsWith("thermal.")));
        assertTrue(rows.stream().noneMatch(row -> "state.abs.duration".equals(row.id())));
    }

    @Test
    void savedReportConfigKeepsKpiTextWithoutChangingClassicBlobs() {
        assertEquals(EngineeringReportTemplate.CLASSIC, InvestigationReportConfig.decode("1111111110").template());
        InvestigationReportConfig v2 = InvestigationReportConfig.engineeringV2()
                .withKpiConfig("gap=1\nspeed=Veh");
        InvestigationReportConfig decoded = InvestigationReportConfig.decode(v2.encode());
        assertEquals("gap=1\nspeed=Veh", decoded.kpiConfig());
        assertTrue(decoded.shows(EngineeringReportSection.ENGINEERING_KPIS));
        InvestigationReportConfig headerOnly = InvestigationReportConfig.decode(v2.encode().substring(0, 15));
        assertEquals(EngineeringReportTemplate.ENGINEERING_V2, headerOnly.template());
        assertEquals("", headerOnly.kpiConfig());
    }

    private static EngineeringKpiResult one(List<EngineeringKpiResult> rows, String id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    private static EngineeringKpiSeries speed(String name, String unit, String file, List<Sample> samples) {
        return EngineeringKpiSeries.present("speed", name, unit, file, samples);
    }

    private static List<Sample> constant(double value, int startSecond, int endSecond) {
        List<Sample> samples = new ArrayList<>();
        for (int second = startSecond; second <= endSecond; second++) {
            samples.add(sample(second, value, true));
        }
        return samples;
    }

    private static Sample sample(double seconds, double value, boolean valid) {
        return new Sample(Math.round(seconds * 1_000_000_000d), value, valid, "");
    }

    private static EngineeringReportModel model(List<EngineeringReportSection> sections, List<EngineeringKpiResult> kpis) {
        return EngineeringReportModel.builder()
                .template(EngineeringReportTemplate.ENGINEERING_V2)
                .sections(sections)
                .kpis(kpis)
                .header(new EngineeringReportModel.Header(
                        "KPI pack", "Tata Sierra", "KPI pack", Instant.parse("2026-09-21T13:30:54Z"), "2.0", "Ada"))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        "KPI pack", "", InvestigationStatus.DRAFT, "", "", 0))
                .build();
    }
}

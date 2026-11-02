package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteMapReportTest {

    private static final String SECRET = "AIzaSyTESTKEY1234567890";
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    void routeEvidenceIncludesMapSectionAndRelativeAsset() {
        RouteMapSnapshot snapshot = sample();
        String html = render(investigation("project-a", "inv-a"),
                EngineeringReportModel.RouteMapSection.image(snapshot, "assets/map-route.png"));
        assertTrue(html.contains("Route Map"));
        assertTrue(html.contains("assets/map-route.png"));
        assertTrue(html.contains("GPS Points"));
        assertTrue(html.contains("1,000"));
        assertFalse(html.contains("<iframe"));
        assertFalse(html.contains("maps.google"));
        assertFalse(html.contains(SECRET));
    }

    @Test
    void reportWithoutRouteEvidenceOmitsMapSection() {
        String html = render(EngineeringInvestigation.builder("project-a").name("Plain").build(), null);
        assertFalse(html.contains("Route Map"));
        assertFalse(html.contains("map-route.png"));
    }

    @Test
    void embeddedImageIsPortableAndContainsNoSecret() throws Exception {
        String uri = GraphImageEmbed.pngDataUri(TINY_PNG);
        RouteMapSnapshot snapshot = sample();
        String html = render(investigation("project-a", "inv-a"),
                EngineeringReportModel.RouteMapSection.image(snapshot, uri));
        assertTrue(html.contains("data:image/png;base64,"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains(SECRET));
        assertFalse(snapshot.toJson().contains(SECRET));
        var file = Files.createTempFile("route-report", ".html");
        try {
            Files.writeString(file, html, StandardCharsets.UTF_8);
            String reopened = Files.readString(file);
            assertTrue(reopened.contains("data:image/png;base64,"));
            assertTrue(reopened.contains("Route Map"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void boundsCoverTheFullRoute() {
        RouteMapSnapshot snapshot = new RouteMapSnapshot(
                1_000_000_000L, 5_000_000_000L, 3, 1200.0,
                12.0, 12.5, 77.0, 77.4, true);
        assertTrue(snapshot.covers(12.0, 77.0));
        assertTrue(snapshot.covers(12.5, 77.4));
        assertTrue(snapshot.covers(12.2, 77.2));
        assertFalse(snapshot.covers(11.9, 77.2));
        assertFalse(snapshot.covers(12.2, 78.0));
    }

    @Test
    void rendererDoesNotReprocessMeasurementFiles() {
        String html = render(investigation("project-a", "inv-a"),
                EngineeringReportModel.RouteMapSection.image(sample(), "assets/map-route.png"));
        assertTrue(html.contains("Generated without re-parsing measurement files."));
    }

    @Test
    void mapRenderFailureStillProducesTheReport() {
        String html = render(investigation("project-a", "inv-a"),
                EngineeringReportModel.RouteMapSection.unavailable(sample()));
        assertTrue(html.contains("Map preview unavailable for this report."));
        assertTrue(html.contains("Engineering Analysis Report"));
        assertTrue(html.contains("GPS Points"));
        assertFalse(html.contains("<img"));
    }

    @Test
    void allMeasurementsSummaryPrintsAggregateMetricsWithoutFilenames() {
        RouteMapSummary summary = new RouteMapSummary(true, "All Measurements", 73, 73, 73);
        EngineeringReportModel.RouteMapSection section = new EngineeringReportModel.RouteMapSection(
                "Route Map", "assets/map-route.png", "", "", "", "", "1188.38 km", "634,781", summary);
        String html = render(investigation("project-a", "inv-a"), section);
        assertTrue(html.contains("<h2>Route Map</h2>"));
        assertEquals(1, html.split("<h2>Route Map</h2>", -1).length - 1);
        assertTrue(html.contains("assets/map-route.png"));
        assertTrue(html.contains("1188.38 km"));
        assertTrue(html.contains("634,781"));
        assertTrue(html.contains(">73<"));
        assertTrue(html.contains("Independent segments"));
        assertFalse(html.contains(".dat"));
        assertFalse(html.contains("<iframe"));
    }

    @Test
    void singleMeasurementSummaryNamesOnlyThatFile() {
        RouteMapSummary summary = new RouteMapSummary(false, "alpha.dat", 1, 1, 1);
        EngineeringReportModel.RouteMapSection section = new EngineeringReportModel.RouteMapSection(
                "Route Map", "assets/map-route.png", "", "", "", "", "7.0 km", "6,126", summary);
        String html = render(investigation("project-a", "inv-a"), section);
        assertTrue(html.contains("alpha.dat"));
        assertTrue(html.contains("7.0 km"));
        assertFalse(html.contains("GPS-capable"));
        assertFalse(html.contains("beta.dat"));
    }

    @Test
    void projectRouteDoesNotAppearInAnotherProjectsReport() {
        String projectA = render(investigation("project-a", "inv-a"),
                EngineeringReportModel.RouteMapSection.image(sample(), "assets/project-a-map.png"));
        String projectB = render(EngineeringInvestigation.builder("project-b").name("Other").build(), null);
        assertTrue(projectA.contains("project-a-map.png"));
        assertFalse(projectB.contains("project-a-map.png"));
        assertFalse(projectB.contains("Route Map"));
    }

    private static String render(EngineeringInvestigation investigation, EngineeringReportModel.RouteMapSection map) {
        return EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                investigation,
                new EngineeringReportModelBuilder.Context(
                        "StellarAI 1.0.1", "", null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of())
                        .withRouteMap(map)));
    }

    private static EngineeringInvestigation investigation(String projectId, String investigationId) {
        return EngineeringInvestigation.builder(projectId)
                .investigationId(investigationId)
                .name("Route investigation")
                .evidence(List.of(new EngineeringEvidenceItem(
                        "e-route", investigationId, EvidenceKind.ROUTE_MAP, "snap-1", 0, false,
                        "", "", 0L, "Route map", "Route map", Instant.parse("2026-10-13T09:00:00Z"))))
                .build();
    }

    private static RouteMapSnapshot sample() {
        return new RouteMapSnapshot(0L, 12_000_000_000L, 1000, 5400.0, 12.9, 13.1, 77.5, 77.7, true);
    }
}

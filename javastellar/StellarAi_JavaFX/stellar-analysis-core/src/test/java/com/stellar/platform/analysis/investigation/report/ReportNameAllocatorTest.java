package com.stellar.platform.analysis.investigation.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportNameAllocatorTest {

    @Test
    void firstNameUsesUnsuffixedHtml() {
        assertEquals("Analysis.html", ReportNameAllocator.allocate("Analysis", List.of()));
    }

    @Test
    void secondNameUsesFirstSuffix() {
        assertEquals("Analysis_1.html", ReportNameAllocator.allocate("Analysis", List.of("Analysis.html")));
    }

    @Test
    void thirdNameUsesNextSuffix() {
        assertEquals("Analysis_2.html",
                ReportNameAllocator.allocate("Analysis", List.of("Analysis.html", "Analysis_1.html")));
    }

    @Test
    void lowestGapIsReused() {
        assertEquals("Report_2.html", ReportNameAllocator.allocate("Report",
                List.of("Report.html", "Report_1.html", "Report_3.html")));
    }

    @Test
    void caseDifferenceOccupiesTheName() {
        assertEquals("report_1.html", ReportNameAllocator.allocate("report", List.of("REPORT.html")));
        assertTrue(ReportNameAllocator.nameTaken("REPORT", List.of("report.html"), null));
    }

    @Test
    void htmlSuffixIsStrippedOnceAndRepeatedly() {
        assertEquals("Vehicle Analysis.html",
                ReportNameAllocator.allocate("Vehicle Analysis.html", List.of()));
        assertEquals("Vehicle Analysis.html",
                ReportNameAllocator.allocate("Vehicle Analysis.html.html", List.of()));
    }

    @Test
    void paddedSuffixIsNotTreatedAsAnAllocatedNumber() {
        assertEquals("Report_1.html", ReportNameAllocator.allocate("Report",
                List.of("Report.html", "Report_01.html", "Report_10.html")));
    }

    @Test
    void trimsSpacesAndTrailingPeriods() {
        assertEquals("Report.html", ReportNameAllocator.allocate("  Report.  ", List.of()));
    }

    @Test
    void emptyNameIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReportNameAllocator.allocate("   ", List.of()));
        assertEquals(ReportNameAllocator.NAME_REQUIRED, ex.getMessage());
        assertEquals(ReportNameAllocator.NAME_REQUIRED, ReportNameAllocator.validationError(".html"));
    }

    @Test
    void invalidCharactersAndReservedNamesAreRejected() {
        assertEquals(ReportNameAllocator.NAME_INVALID_CHARACTERS,
                ReportNameAllocator.validationError("Report/Name"));
        assertEquals(ReportNameAllocator.NAME_NOT_ALLOWED, ReportNameAllocator.validationError("CON"));
        assertEquals(ReportNameAllocator.NAME_NOT_ALLOWED, ReportNameAllocator.validationError("lpt1"));
    }

    @Test
    void renameKeepsTheCurrentFile() {
        assertFalse(ReportNameAllocator.nameTaken("Test Report", List.of("Test Report.html"), "Test Report.html"));
        assertTrue(ReportNameAllocator.nameTaken("Test Report", List.of("Test Report.html"), "Other.html"));
    }

    @Test
    void unicodeDisplayNameIsKept() {
        assertEquals("Bremsdruck.html", ReportNameAllocator.allocate("Bremsdruck", List.of()));
    }
}

package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.ReportFactKind;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiCalculator;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringKpiPresentationTest {

    @Test
    void calculatedRowsStayGroupedAndUnavailableRowsBecomeLimitations() {
        EngineeringKpiResult maximum = new EngineeringKpiResult(
                "thermal.tc.max", "Maximum CH01_FRONT_BUMPER_GRILL_AMBIENT", "50.062104", 50.062104,
                "°C", "CH01_FRONT_BUMPER_GRILL_AMBIENT", "tata.mf4", "0.085 s – 1799.787 s",
                EngineeringKpiCalculator.METHOD, "100%", "", ReportFactKind.CALCULATED, "");
        EngineeringKpiResult gnss = new EngineeringKpiResult(
                "vehicle.distance.gnss", "Distance from GNSS speed", "Unavailable", null,
                "", "SPEED_OVER_GROUND", "tata.mf4", "",
                EngineeringKpiCalculator.METHOD, "", "", ReportFactKind.UNAVAILABLE,
                "Unit '' is not a supported speed unit. Distance was not calculated.");
        List<EngineeringKpiPresentation.Row> shown = EngineeringKpiPresentation.calculated(List.of(maximum, gnss));
        assertEquals(1, shown.size());
        assertEquals("Temperature", shown.getFirst().domain());
        assertEquals("Maximum", shown.getFirst().name());
        assertEquals("50.062104", shown.getFirst().value());
        assertEquals("°C", shown.getFirst().unit());
        assertTrue(shown.getFirst().detail().contains("CH01_FRONT_BUMPER_GRILL_AMBIENT"));
        assertTrue(shown.getFirst().detail().contains(EngineeringKpiCalculator.METHOD));
        List<String> limits = EngineeringKpiPresentation.limitations(List.of(maximum, gnss));
        assertEquals(1, limits.size());
        assertTrue(limits.getFirst().contains("not a supported speed unit"));
        assertTrue(limits.getFirst().contains("SPEED_OVER_GROUND"));
    }
}

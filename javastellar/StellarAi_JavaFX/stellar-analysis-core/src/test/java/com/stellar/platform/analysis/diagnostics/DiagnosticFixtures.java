package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.AutomotiveAnalysisFixtures;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;

final class DiagnosticFixtures {

    private DiagnosticFixtures() {
    }

    static SignalCatalogEntry entry(SignalCatalogKey key, String unit) {
        return new SignalCatalogEntry(key, unit, 20, 0L, 20_000_000_000L, 0d, 200d, "synth.mf4", "veh.dbc");
    }

    static List<SignalCatalogEntry> workstationCatalog() {
        return List.of(
                entry(AutomotiveAnalysisFixtures.RPM, "rpm"),
                entry(AutomotiveAnalysisFixtures.SPEED, "km/h"),
                entry(AutomotiveAnalysisFixtures.REQ_TQ, "Nm"),
                entry(AutomotiveAnalysisFixtures.ACT_TQ, "Nm"),
                entry(AutomotiveAnalysisFixtures.BRAKE_P, "bar"),
                entry(AutomotiveAnalysisFixtures.BRAKE_SW, ""),
                entry(AutomotiveAnalysisFixtures.STEER, "deg"),
                entry(AutomotiveAnalysisFixtures.COOLANT, "°C"),
                entry(AutomotiveAnalysisFixtures.GEAR, "")
        );
    }

    static InMemoryAnalysisSampleSource thermalSustained() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 14; i++) {
            long t = i * 1_000_000_000L;
            src.add(AutomotiveAnalysisFixtures.COOLANT, t, i < 2 ? 90d : 107.3d);
        }
        return src;
    }

    static InMemoryAnalysisSampleSource thermalSpike() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(AutomotiveAnalysisFixtures.COOLANT, 0, 90);
        src.add(AutomotiveAnalysisFixtures.COOLANT, 1_000_000_000L, 108);
        src.add(AutomotiveAnalysisFixtures.COOLANT, 2_000_000_000L, 90);
        return src;
    }

    static InMemoryAnalysisSampleSource brakeDelayed() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(AutomotiveAnalysisFixtures.BRAKE_SW, 0, 0, 0L, "OFF");
        src.add(AutomotiveAnalysisFixtures.BRAKE_P, 0, 2);
        src.add(AutomotiveAnalysisFixtures.BRAKE_SW, 1_000_000_000L, 1, 1L, "ON");
        src.add(AutomotiveAnalysisFixtures.BRAKE_P, 1_000_000_000L, 2);
        src.add(AutomotiveAnalysisFixtures.BRAKE_SW, 1_690_000_000L, 1, 1L, "ON");
        src.add(AutomotiveAnalysisFixtures.BRAKE_P, 1_690_000_000L, 40);
        return src;
    }

    static InMemoryAnalysisSampleSource brakeNormal() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(AutomotiveAnalysisFixtures.BRAKE_SW, 0, 0, 0L, "OFF");
        src.add(AutomotiveAnalysisFixtures.BRAKE_P, 0, 2);
        src.add(AutomotiveAnalysisFixtures.BRAKE_SW, 1_000_000_000L, 1, 1L, "ON");
        src.add(AutomotiveAnalysisFixtures.BRAKE_P, 1_000_000_000L, 40);
        return src;
    }

    static InMemoryAnalysisSampleSource torqueDeviation() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 5; i++) {
            long t = i * 1_000_000_000L;
            src.add(AutomotiveAnalysisFixtures.REQ_TQ, t, 80);
            src.add(AutomotiveAnalysisFixtures.ACT_TQ, t, 20);
        }
        return src;
    }

    static InMemoryAnalysisSampleSource torqueTracking() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 5; i++) {
            long t = i * 1_000_000_000L;
            src.add(AutomotiveAnalysisFixtures.REQ_TQ, t, 20 + i);
            src.add(AutomotiveAnalysisFixtures.ACT_TQ, t, 20 + i);
        }
        return src;
    }

    static InMemoryAnalysisSampleSource fastSteer() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        src.add(AutomotiveAnalysisFixtures.STEER, 0, 0);
        src.add(AutomotiveAnalysisFixtures.STEER, 50_000_000L, 50);
        return src;
    }

    static InMemoryAnalysisSampleSource fahrenheitCoolantSustained() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        for (int i = 0; i <= 14; i++) {
            src.add(AutomotiveAnalysisFixtures.COOLANT, i * 1_000_000_000L, i < 2 ? 194d : 225d);
        }
        return src;
    }
}

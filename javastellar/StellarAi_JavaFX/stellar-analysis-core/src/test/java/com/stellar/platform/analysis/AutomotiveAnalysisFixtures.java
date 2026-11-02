package com.stellar.platform.analysis;

import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

/**
 * Deterministic synthetic automotive series with known expected outcomes.
 */
public final class AutomotiveAnalysisFixtures {

    public static final SignalCatalogKey RPM = key("EngineRPM", "ECM", 0x100);
    public static final SignalCatalogKey SPEED = key("VehicleSpeed", "ABS", 0x200);
    public static final SignalCatalogKey REQ_TQ = key("RequestedTorque", "ECM", 0x300);
    public static final SignalCatalogKey ACT_TQ = key("ActualTorque", "ECM", 0x301);
    public static final SignalCatalogKey BRAKE_P = key("BrakePressure", "ABS", 0x400);
    public static final SignalCatalogKey BRAKE_SW = key("BrakePedalState", "ABS", 0x401);
    public static final SignalCatalogKey STEER = key("SteeringAngle", "SAS", 0x500);
    public static final SignalCatalogKey COOLANT = key("CoolantTemperature", "ECM", 0x600);
    public static final SignalCatalogKey GEAR = key("GearState", "TCM", 0x700);

    private AutomotiveAnalysisFixtures() {
    }

    public static InMemoryAnalysisSampleSource workstation() {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        // 1 Hz from t=0..10s. RPM 800,1000,...,1800 then hold.
        for (int i = 0; i <= 10; i++) {
            long t = i * 1_000_000_000L;
            src.add(RPM, t, 800 + i * 100);
            src.add(SPEED, t, i * 8.0);
            src.add(REQ_TQ, t, 20 + i * 5.0);
            src.add(ACT_TQ, t, 18 + i * 5.0);
            src.add(STEER, t, i < 5 ? 0 : 12.0);
        }
        // Coolant crosses 105 with hysteresis-friendly chatter around the threshold.
        double[] coolant = {90, 100, 106, 107, 104, 106, 103, 101, 100, 99, 98};
        for (int i = 0; i < coolant.length; i++) {
            src.add(COOLANT, i * 1_000_000_000L, coolant[i]);
        }
        // Brake pedal 0/1 with two presses.
        int[] brake = {0, 0, 1, 1, 1, 0, 0, 1, 1, 0, 0};
        for (int i = 0; i < brake.length; i++) {
            src.add(BRAKE_SW, i * 1_000_000_000L, brake[i], (long) brake[i], brake[i] == 1 ? "ON" : "OFF");
            src.add(BRAKE_P, i * 1_000_000_000L, brake[i] == 1 ? 80.0 : 2.0);
        }
        // Gear PARK then DRIVE then PARK.
        String[] gear = {"PARK", "PARK", "DRIVE", "DRIVE", "DRIVE", "DRIVE", "PARK", "PARK", "PARK", "PARK", "PARK"};
        for (int i = 0; i < gear.length; i++) {
            src.add(GEAR, i * 1_000_000_000L, "PARK".equals(gear[i]) ? 0 : 1, null, gear[i]);
        }
        return src;
    }

    public static SignalCatalogKey key(String name, String message, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, message, canId, 1, "synth.mf4", "veh.dbc");
    }
}

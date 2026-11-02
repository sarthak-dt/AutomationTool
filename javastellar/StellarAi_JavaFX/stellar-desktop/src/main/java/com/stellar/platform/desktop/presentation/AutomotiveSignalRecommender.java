package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * High-confidence vehicle-analysis recommendations. Never auto-selects a random signal, and never
 * treats checksum or process-counter channels as recommended driving signals.
 */
public final class AutomotiveSignalRecommender {

    public enum Kind {
        VEHICLE_SPEED,
        GPS_SPEED,
        ENGINE_RPM,
        THROTTLE,
        BRAKE,
        STEERING
    }

    public record Recommendation(Kind kind, SignalCatalogEntry entry) {
    }

    private AutomotiveSignalRecommender() {
    }

    public static List<Recommendation> recommend(Collection<SignalCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return List.of();
        }
        Recommendation speed = null;
        Recommendation gpsSpeed = null;
        Recommendation rpm = null;
        Recommendation throttle = null;
        Recommendation brake = null;
        Recommendation steering = null;
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null || excluded(entry)) {
                continue;
            }
            String text = tokens(entry);
            if (gpsSpeed == null && gpsSpeed(text)) {
                gpsSpeed = new Recommendation(Kind.GPS_SPEED, entry);
                continue;
            }
            if (speed == null && vehicleSpeed(text)) {
                speed = new Recommendation(Kind.VEHICLE_SPEED, entry);
                continue;
            }
            if (rpm == null && engineRpm(text)) {
                rpm = new Recommendation(Kind.ENGINE_RPM, entry);
                continue;
            }
            if (throttle == null && throttle(text)) {
                throttle = new Recommendation(Kind.THROTTLE, entry);
                continue;
            }
            if (brake == null && brake(text)) {
                brake = new Recommendation(Kind.BRAKE, entry);
                continue;
            }
            if (steering == null && steering(text)) {
                steering = new Recommendation(Kind.STEERING, entry);
            }
        }
        List<Recommendation> out = new ArrayList<>();
        add(out, speed);
        add(out, gpsSpeed);
        add(out, rpm);
        add(out, throttle);
        add(out, brake);
        add(out, steering);
        return List.copyOf(out);
    }

    public static Set<SignalCatalogKey> recommendedKeys(Collection<SignalCatalogEntry> catalog) {
        LinkedHashSet<SignalCatalogKey> keys = new LinkedHashSet<>();
        for (Recommendation recommendation : recommend(catalog)) {
            keys.add(recommendation.entry().key());
        }
        return keys;
    }

    private static void add(List<Recommendation> out, Recommendation recommendation) {
        if (recommendation != null) {
            out.add(recommendation);
        }
    }

    private static boolean excluded(SignalCatalogEntry entry) {
        String text = tokens(entry);
        return text.contains("CHECKSUM")
                || text.contains("CRC")
                || text.contains("PROCESS_COUNTER")
                || text.contains("PROCESS COUNTER")
                || text.contains("ALIVE") && text.contains("COUNTER")
                || text.contains("ROLLING_COUNTER");
    }

    private static boolean gpsSpeed(String text) {
        return (text.contains("GPS") || text.contains("GNSS") || text.contains("GROUND"))
                && (text.contains("SPEED") || text.contains("SOG") || text.contains("VELOCITY"));
    }

    private static boolean vehicleSpeed(String text) {
        if (gpsSpeed(text) || text.contains("WHEEL") && text.contains("SPEED")) {
            return text.contains("VEHICLE") && text.contains("SPEED");
        }
        return (text.contains("VEHICLE") && text.contains("SPEED"))
                || text.contains("SPEEDO")
                || text.equals("SPEED")
                || text.contains("VSS");
    }

    private static boolean engineRpm(String text) {
        return text.contains("RPM") || (text.contains("ENGINE") && (text.contains("SPEED") || text.contains("REV")));
    }

    private static boolean throttle(String text) {
        return text.contains("THROTTLE") || text.contains("ACCEL_PEDAL") || text.contains("ACCELERATOR");
    }

    private static boolean brake(String text) {
        return text.contains("BRAKE") && !text.contains("BREAK");
    }

    private static boolean steering(String text) {
        return text.contains("STEER");
    }

    private static String tokens(SignalCatalogEntry entry) {
        StringBuilder text = new StringBuilder();
        append(text, entry.signalName());
        append(text, entry.messageName());
        append(text, entry.unit());
        append(text, entry.sourceFile());
        return text.toString();
    }

    private static void append(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(value.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_'));
    }
}

package com.stellar.platform.processing.drive;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves drive-analysis roles from catalog names/units. Never uses hard-coded CAN IDs.
 */
public final class DriveSemanticResolver {

    public Map<DriveSemanticRole, SignalCatalogEntry> resolve(Collection<SignalCatalogEntry> catalog) {
        EnumMap<DriveSemanticRole, SignalCatalogEntry> out = new EnumMap<>(DriveSemanticRole.class);
        if (catalog == null) {
            return Map.of();
        }
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null) {
                continue;
            }
            String text = tokens(entry);
            consider(out, DriveSemanticRole.BRAKE_PEDAL, entry, brakePedal(text));
            consider(out, DriveSemanticRole.BRAKE_PRESSURE, entry, brakePressure(text));
            consider(out, DriveSemanticRole.BRAKE_SWITCH, entry, brakeSwitch(text));
            consider(out, DriveSemanticRole.BRAKE_STATUS, entry, brakeStatus(text) && !brakePedal(text)
                    && !brakePressure(text) && !brakeSwitch(text));
            consider(out, DriveSemanticRole.FUEL_RATE, entry, fuelRate(text));
            consider(out, DriveSemanticRole.FUEL_USED, entry, fuelUsed(text) && !fuelRate(text));
            consider(out, DriveSemanticRole.FUEL_LEVEL, entry, fuelLevel(text) && !fuelRate(text) && !fuelUsed(text));
            consider(out, DriveSemanticRole.VEHICLE_SPEED, entry, vehicleSpeed(text));
            consider(out, DriveSemanticRole.LONGITUDINAL_ACCEL, entry, longAccel(text));
        }
        return Map.copyOf(out);
    }

    public Optional<SignalCatalogEntry> firstBrakeEvidence(Map<DriveSemanticRole, SignalCatalogEntry> roles) {
        if (roles == null) {
            return Optional.empty();
        }
        SignalCatalogEntry pedal = roles.get(DriveSemanticRole.BRAKE_PEDAL);
        if (pedal != null) {
            return Optional.of(pedal);
        }
        SignalCatalogEntry pressure = roles.get(DriveSemanticRole.BRAKE_PRESSURE);
        if (pressure != null) {
            return Optional.of(pressure);
        }
        SignalCatalogEntry sw = roles.get(DriveSemanticRole.BRAKE_SWITCH);
        if (sw != null) {
            return Optional.of(sw);
        }
        return Optional.ofNullable(roles.get(DriveSemanticRole.BRAKE_STATUS));
    }

    public Optional<SignalCatalogKey> key(Map<DriveSemanticRole, SignalCatalogEntry> roles, DriveSemanticRole role) {
        if (roles == null || role == null) {
            return Optional.empty();
        }
        SignalCatalogEntry entry = roles.get(role);
        return entry == null ? Optional.empty() : Optional.of(entry.key());
    }

    private static void consider(EnumMap<DriveSemanticRole, SignalCatalogEntry> out,
                                 DriveSemanticRole role,
                                 SignalCatalogEntry entry,
                                 boolean match) {
        if (match && !out.containsKey(role)) {
            out.put(role, entry);
        }
    }

    public static String tokens(SignalCatalogEntry entry) {
        String name = entry.signalName() == null ? "" : entry.signalName();
        String message = entry.messageName() == null ? "" : entry.messageName();
        String unit = entry.unit() == null ? "" : entry.unit();
        return (name + " " + message + " " + unit).toLowerCase(Locale.ROOT);
    }

    static boolean brakePedal(String text) {
        return contains(text, "brake") && contains(text, "pedal");
    }

    static boolean brakePressure(String text) {
        return contains(text, "brake") && contains(text, "press");
    }

    static boolean brakeSwitch(String text) {
        return contains(text, "brake") && (contains(text, "switch") || contains(text, "sw")
                || contains(text, "onoff") || contains(text, "on_off"));
    }

    static boolean brakeStatus(String text) {
        return contains(text, "brake") && (contains(text, "stat") || contains(text, "active")
                || contains(text, "applied") || contains(text, "light"));
    }

    static boolean fuelRate(String text) {
        if (fuelLevel(text)) {
            return false;
        }
        return (contains(text, "fuel") || contains(text, "inj"))
                && (contains(text, "rate") || contains(text, "flow") || contains(text, "consumpt"));
    }

    static boolean fuelUsed(String text) {
        if (fuelLevel(text) || fuelRate(text)) {
            return false;
        }
        return contains(text, "fuel") && (contains(text, "used") || contains(text, "consumed")
                || contains(text, "total"));
    }

    static boolean fuelLevel(String text) {
        return contains(text, "fuel") && (contains(text, "level") || contains(text, "tank")
                || contains(text, "gauge") || contains(text, "remain"));
    }

    public static boolean vehicleSpeed(String text) {
        if (contains(text, "wheel") || contains(text, "gps")) {
            return false;
        }
        return (contains(text, "speed") && (contains(text, "veh") || contains(text, "vehicle")
                || contains(text, "vss") || text.contains("speedo")))
                || text.contains("vehiclespeed");
    }

    static boolean longAccel(String text) {
        return (contains(text, "accel") || contains(text, "acc "))
                && (contains(text, "long") || contains(text, "x") || contains(text, "longitudinal"));
    }

    private static boolean contains(String text, String token) {
        return text != null && text.contains(token);
    }
}

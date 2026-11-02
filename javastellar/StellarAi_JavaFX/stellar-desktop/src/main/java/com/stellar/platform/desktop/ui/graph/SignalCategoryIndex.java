package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.map.GpsLocationSignalIndex;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Groups the signals of one measurement into the engineering categories shown in the signal
 * panel. Built once per catalog change and then queried per row, so classification never runs
 * per repaint.
 *
 * <p>Rules are token/metadata based — never an exact list of known signal names. Unknown
 * signals stay visible under {@code Other}. Grouping does not change {@code SignalCatalogKey}
 * identity or sample data.
 *
 * <p>Location signals are decided by {@link GpsLocationSignalIndex} rather than by name matching,
 * because the generic rules below would otherwise scatter a GPS group: {@code SPEED_OVER_GROUND}
 * would land in vehicle dynamics and a bare {@code QUALITY} channel nowhere at all.
 */
public final class SignalCategoryIndex {

    public static final String GPS_LOCATION = "GPS / Location";
    public static final String OTHER = "Other";
    /** Bump when token rules change; grouping is recomputed, not persisted. */
    public static final int CLASSIFIER_VERSION = 1;

    public static final SignalCategoryIndex EMPTY = new SignalCategoryIndex(GpsLocationSignalIndex.EMPTY);

    private final GpsLocationSignalIndex locations;
    /** One classification per catalog key. Built with the index, not per row repaint. */
    private final Map<SignalCatalogKey, String> categoryByKey;
    private final Map<String, Integer> countByGroup;

    private SignalCategoryIndex(GpsLocationSignalIndex locations) {
        this(locations, Map.of(), Map.of());
    }

    private SignalCategoryIndex(
            GpsLocationSignalIndex locations,
            Map<SignalCatalogKey, String> categoryByKey,
            Map<String, Integer> countByGroup
    ) {
        this.locations = Objects.requireNonNull(locations, "locations");
        this.categoryByKey = categoryByKey == null ? Map.of() : categoryByKey;
        this.countByGroup = countByGroup == null ? Map.of() : countByGroup;
    }

    public static SignalCategoryIndex build(Collection<SignalCatalogEntry> catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return EMPTY;
        }
        GpsLocationSignalIndex locations = GpsLocationSignalIndex.build(catalog);
        Map<SignalCatalogKey, String> categories = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        SignalCategoryIndex index = new SignalCategoryIndex(locations, categories, counts);
        for (SignalCatalogEntry entry : catalog) {
            if (entry == null || entry.key() == null) {
                continue;
            }
            String group = index.classify(entry).group();
            if (categories.putIfAbsent(entry.key(), group) == null) {
                counts.merge(group, 1, Integer::sum);
            }
        }
        return index;
    }

    /** Keys classified when this index was built. */
    public int indexedSize() {
        return categoryByKey.size();
    }

    /** Location signals of this measurement, for the group's header count. */
    public GpsLocationSignalIndex locations() {
        return locations;
    }

    public String categoryOf(SignalCatalogEntry entry) {
        if (entry == null || entry.key() == null) {
            return OTHER;
        }
        String cached = categoryByKey.get(entry.key());
        return cached != null ? cached : classify(entry).group();
    }

    /**
     * Layered classification: GPS cohort metadata, then normalized name/message/unit tokens,
     * then {@link #OTHER}. Confidence is for tests/diagnostics only.
     */
    public Classification classify(SignalCatalogEntry entry) {
        if (entry == null) {
            return new Classification(OTHER, "empty", 0);
        }
        if (locations.isLocationSignal(entry)) {
            return new Classification(GPS_LOCATION, "gps-cohort", 90);
        }
        String n = classificationText(entry);
        // Domain-specific signals win before generic words such as POSITION. This prevents
        // ThrottlePosition / PedalPosition from being misclassified as geographic position.
        if (hasAny(n, "RPM", "ENGINE", "TORQUE", "THROTTLE", "PEDAL", "LOAD", "BOOST", "INTAKE")) {
            return new Classification("Powertrain", "semantic-token", 70);
        }
        if (hasAny(n, "SPEED", "VELOCITY", "ACCEL", "YAW", "PITCH", "ROLL", "STEER")) {
            return new Classification("Vehicle dynamics", "semantic-token", 70);
        }
        if (hasAny(n, "GPS", "GNSS", "LATITUDE", "LONGITUDE", "ALTITUDE", "POSITION")) {
            return new Classification(GPS_LOCATION, "semantic-token", 70);
        }
        if (hasAny(n, "FUEL", "INJECT", "LAMBDA", "OXY", "AFR")) {
            return new Classification("Fuel / emissions", "semantic-token", 70);
        }
        if (hasAny(n, "TEMP", "COOLANT", "THERM", "FAN", "HEAT")) {
            return new Classification("Thermal", "semantic-token", 70);
        }
        if (hasAny(n, "BRAKE", "ABS", "ESP", "ESC")) {
            return new Classification("Braking / stability", "semantic-token", 70);
        }
        if (hasAny(n, "BATT", "VOLT", "CURRENT", "SOC", "SOH", "CHARGE", "HV_", "LV_")) {
            return new Classification("Electrical / energy", "semantic-token", 70);
        }
        if (hasAny(n, "DOOR", "LOCK", "LIGHT", "WIPER", "SEAT", "WAKEUP", "IMMOB", "TBOX", "BCM", "BCCM")) {
            return new Classification("Body / comfort", "semantic-token", 70);
        }
        if (hasAny(n, "DIAG", "DTC", "FAULT", "ERROR", "STATUS", "STATE", "CHECKSUM", "CHKSUM",
                "COUNTER", "MUX")) {
            return new Classification("Diagnostics / status", "semantic-token", 70);
        }
        return new Classification(OTHER, "fallback", 10);
    }

    /**
     * Normalized haystack: signal name, DBC message, unit. Separators and camelCase become
     * spaces so tokens match OEM and native names without an exact-name table.
     */
    static String classificationText(SignalCatalogEntry entry) {
        if (entry == null || entry.key() == null) {
            return "";
        }
        return normalizeEvidence(entry.key().signalName())
                + " " + normalizeEvidence(entry.messageName())
                + " " + normalizeEvidence(entry.unit());
    }

    static String normalizeEvidence(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String spaced = raw.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ');
        return spaced.toUpperCase(Locale.ROOT);
    }

    public record Classification(String group, String reason, int confidence) {
        public Classification {
            group = group == null || group.isBlank() ? OTHER : group;
            reason = reason == null ? "" : reason;
        }
    }

    public static int rank(String category) {
        return switch (category) {
            case GPS_LOCATION -> 0;
            case "Powertrain" -> 1;
            case "Vehicle dynamics" -> 2;
            case "Braking / stability" -> 3;
            case "Fuel / emissions" -> 4;
            case "Thermal" -> 5;
            case "Electrical / energy" -> 6;
            case "Body / comfort" -> 7;
            case "Diagnostics / status" -> 8;
            default -> 9;
        };
    }

    private static boolean hasAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}

package com.stellar.platform.parser.mf4.block;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Known MDF 4.x block identifiers. Structurally readable unknowns map to {@link #UNKNOWN}.
 */
public enum MdfBlockType {
    HD("##HD"),
    FH("##FH"),
    CH("##CH"),
    AT("##AT"),
    EV("##EV"),
    DG("##DG"),
    CG("##CG"),
    CN("##CN"),
    CC("##CC"),
    CA("##CA"),
    SI("##SI"),
    TX("##TX"),
    MD("##MD"),
    DL("##DL"),
    HL("##HL"),
    DT("##DT"),
    DZ("##DZ"),
    SD("##SD"),
    SR("##SR"),
    RD("##RD"),
    UNKNOWN(null);

    private static final Map<String, MdfBlockType> BY_ID;

    static {
        BY_ID = java.util.HashMap.newHashMap(32);
        for (MdfBlockType type : values()) {
            if (type.id != null) {
                BY_ID.put(type.id, type);
            }
        }
    }

    private final String id;

    MdfBlockType(String id) {
        this.id = id;
    }

    public Optional<String> id() {
        return Optional.ofNullable(id);
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    public static MdfBlockType fromIdBytes(byte[] idBytes) {
        if (idBytes == null || idBytes.length < 4) {
            return UNKNOWN;
        }
        String id = new String(idBytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return fromId(id);
    }

    public static MdfBlockType fromId(String id) {
        if (id == null) {
            return UNKNOWN;
        }
        MdfBlockType type = BY_ID.get(id);
        if (type != null) {
            return type;
        }
        // tolerate lower-case / odd spacing
        return BY_ID.getOrDefault(id.toUpperCase(Locale.ROOT), UNKNOWN);
    }
}

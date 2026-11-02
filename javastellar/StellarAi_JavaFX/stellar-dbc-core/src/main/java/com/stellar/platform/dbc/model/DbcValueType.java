package com.stellar.platform.dbc.model;

/**
 * Signedness of a DBC signal raw value.
 */
public enum DbcValueType {
    UNSIGNED,
    SIGNED;

    public static DbcValueType fromDbcSign(char sign) {
        return sign == '-' ? SIGNED : UNSIGNED;
    }
}

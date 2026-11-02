package com.stellar.platform.dbc.model;

/**
 * How the extracted bits of a DBC signal are interpreted before factor and offset.
 * <p>
 * {@code SIG_VALTYPE_} value 1 selects {@link #IEEE_FLOAT32} and value 2 selects
 * {@link #IEEE_FLOAT64}. The {@code SG_} {@code +}/{@code -} sign applies only to
 * {@link #INTEGER}.
 */
public enum DbcRawEncoding {
    INTEGER,
    IEEE_FLOAT32,
    IEEE_FLOAT64
}

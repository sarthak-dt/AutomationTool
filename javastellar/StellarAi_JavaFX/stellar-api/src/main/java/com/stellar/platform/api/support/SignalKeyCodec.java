package com.stellar.platform.api.support;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Stable transport encoding for {@link SignalCatalogKey}. */
public final class SignalKeyCodec {

    private static final String SEP = "|";

    private SignalKeyCodec() {
    }

    public static String encode(SignalCatalogKey key) {
        return encodePart(key.signalName())
                + SEP + key.sourceType().name()
                + SEP + encodePart(key.messageName())
                + SEP + key.canId()
                + SEP + key.channel()
                + SEP + encodePart(key.sourceFile())
                + SEP + encodePart(key.sourceDatabase());
    }

    public static SignalCatalogKey decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("signalKey must not be blank");
        }
        String[] parts = encoded.split("\\|", -1);
        if (parts.length < 7) {
            throw new IllegalArgumentException("Malformed signalKey");
        }
        return new SignalCatalogKey(
                decodePart(parts[0]),
                SignalSourceType.valueOf(parts[1].toUpperCase(Locale.ROOT)),
                decodePart(parts[2]),
                Long.parseLong(parts[3]),
                Integer.parseInt(parts[4]),
                decodePart(parts[5]),
                decodePart(parts[6])
        );
    }

    private static String encodePart(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String decodePart(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}

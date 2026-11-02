package com.stellar.platform.server.decoder;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.ArrayList;
import java.util.List;

/**
 * Opaque-enough canonical identity for one decoded signal inside one validation result.
 * <p>
 * Form: {@code v1|sourceId|name|sourceType|message|canId|channel|sourceFile|sourceDatabase}.
 * Backslash and pipe characters inside text fields are escaped. {@code sourceId} keeps two
 * measurements that share a catalog key from being treated as one signal. The catalog fields
 * after {@code sourceId} are the engineering identity used for processed-versus-fresh comparison.
 */
public final class DecoderCanonicalIds {

    private DecoderCanonicalIds() {
    }

    public static String canonical(String sourceId, SignalCatalogKey key) {
        return String.join("|",
                "v1",
                escape(sourceId),
                escape(key.signalName()),
                key.sourceType().name(),
                escape(key.messageName()),
                Long.toString(key.canId()),
                Integer.toString(key.channel()),
                escape(key.sourceFile()),
                escape(key.sourceDatabase()));
    }

    /** Catalog identity without the project/file source id. */
    public static String signalIdentity(SignalCatalogKey key) {
        return String.join("|",
                escape(key.signalName()),
                key.sourceType().name(),
                escape(key.messageName()),
                Long.toString(key.canId()),
                Integer.toString(key.channel()),
                escape(key.sourceFile()),
                escape(key.sourceDatabase()));
    }

    public static boolean isCanonical(String signal) {
        return signal != null && signal.startsWith("v1|");
    }

    public static Parsed parse(String signal) {
        List<String> parts = split(signal);
        if (parts.size() != 9 || !"v1".equals(parts.get(0))) {
            throw new IllegalArgumentException("signal identity is malformed");
        }
        SignalSourceType sourceType;
        long canId;
        int channel;
        try {
            sourceType = SignalSourceType.valueOf(parts.get(3));
            canId = Long.parseLong(parts.get(5));
            channel = Integer.parseInt(parts.get(6));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("signal identity is malformed");
        }
        return new Parsed(parts.get(1), new SignalCatalogKey(
                parts.get(2), sourceType, parts.get(4), canId, channel, parts.get(7), parts.get(8)));
    }

    private static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length()) {
                current.append(value.charAt(++i));
                continue;
            }
            if (ch == '|') {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        return text.replace("\\", "\\\\").replace("|", "\\|");
    }

    public record Parsed(String sourceId, SignalCatalogKey key) {
    }
}

package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Presentation-only labels for graph traces. Stored signal names are never mutated.
 *
 * <p>Rule: a unique name in the current session is enough. When the same name appears more than
 * once, the smallest real qualifier that distinguishes the traces is appended — channel first,
 * then source file when the channel is still shared.</p>
 */
public final class SignalTraceLabels {

    private SignalTraceLabels() {
    }

    public static String displayName(SignalCatalogKey key, Collection<SignalCatalogEntry> session) {
        if (key == null) {
            return "";
        }
        return labels(session).getOrDefault(key, fallback(key));
    }

    public static String displayName(SignalCatalogEntry entry, Collection<SignalCatalogEntry> session) {
        return entry == null ? "" : displayName(entry.key(), session);
    }

    /** Stable identity → session-scoped display name. Missing keys fall back to the raw name. */
    public static Map<SignalCatalogKey, String> labels(Collection<SignalCatalogEntry> session) {
        Map<SignalCatalogKey, String> out = new LinkedHashMap<>();
        if (session == null || session.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> nameCounts = new HashMap<>();
        Map<NameChannel, Integer> nameChannelCounts = new HashMap<>();
        for (SignalCatalogEntry entry : session) {
            if (entry == null) {
                continue;
            }
            SignalCatalogKey key = entry.key();
            nameCounts.merge(key.signalName(), 1, Integer::sum);
            nameChannelCounts.merge(new NameChannel(key.signalName(), channelToken(key)), 1, Integer::sum);
        }
        for (SignalCatalogEntry entry : session) {
            if (entry == null) {
                continue;
            }
            SignalCatalogKey key = entry.key();
            String name = key.signalName();
            if (nameCounts.getOrDefault(name, 0) <= 1) {
                out.put(key, name);
                continue;
            }
            String channel = channelToken(key);
            if (nameChannelCounts.getOrDefault(new NameChannel(name, channel), 0) <= 1
                    && !channel.isBlank()) {
                out.put(key, name + " \u00b7 " + channel);
            } else {
                String source = sourceToken(key);
                if (!source.isBlank() && !channel.isBlank()) {
                    out.put(key, name + " \u00b7 " + source + " \u00b7 " + channel);
                } else if (!source.isBlank()) {
                    out.put(key, name + " \u00b7 " + source);
                } else if (!channel.isBlank()) {
                    out.put(key, name + " \u00b7 " + channel);
                } else {
                    out.put(key, name);
                }
            }
        }
        return Map.copyOf(out);
    }

    public static String fallback(SignalCatalogKey key) {
        return key == null ? "" : key.signalName();
    }

    public static String channelToken(SignalCatalogKey key) {
        if (key == null) {
            return "";
        }
        if (key.sourceType() == SignalSourceType.DBC_CAN) {
            return "CAN" + key.channel();
        }
        if (key.channel() > 0) {
            return "Ch" + key.channel();
        }
        return "";
    }

    public static String sourceToken(SignalCatalogKey key) {
        if (key == null || key.sourceFile() == null || key.sourceFile().isBlank()) {
            return "";
        }
        String file = key.sourceFile().replace('\\', '/');
        int slash = file.lastIndexOf('/');
        String name = slash < 0 ? file : file.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String sourceFileName(SignalCatalogEntry entry) {
        if (entry == null) {
            return "";
        }
        String path = entry.sourceFile();
        if (path == null || path.isBlank()) {
            path = entry.key().sourceFile();
        }
        if (path == null || path.isBlank()) {
            return "";
        }
        return Path.of(path).getFileName().toString();
    }

    private record NameChannel(String name, String channel) {
        private NameChannel {
            name = Objects.requireNonNullElse(name, "");
            channel = Objects.requireNonNullElse(channel, "");
        }
    }
}

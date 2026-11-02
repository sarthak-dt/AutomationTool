package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Presentation-only source labels for multi-file DBC Mapping.
 * Never used for matching, parsing, or identity — {@link ProjectSourceId} remains canonical.
 */
public final class SourceDisplayNameResolver {

    public record SourceRef(ProjectSourceId sourceId, String fileName, Path path) {
        public SourceRef {
            Objects.requireNonNull(sourceId, "sourceId");
            fileName = fileName == null || fileName.isBlank()
                    ? (path == null || path.getFileName() == null
                    ? sourceId.value()
                    : path.getFileName().toString())
                    : fileName;
        }
    }

    public record DisplayLabel(
            ProjectSourceId sourceId,
            String alias,
            String distinguishingName,
            String fullFileName,
            String fullPath
    ) {
    }

    private SourceDisplayNameResolver() {
    }

    /**
     * Resolves aliases ({@code Source A/B/C…}) and compact distinguishing filenames
     * for the given project measurement sources (order preserved).
     */
    public static Map<ProjectSourceId, DisplayLabel> resolve(List<SourceRef> sources) {
        if (sources == null || sources.isEmpty()) {
            return Map.of();
        }
        List<SourceRef> ordered = new ArrayList<>(sources.size());
        for (SourceRef ref : sources) {
            if (ref != null) {
                ordered.add(ref);
            }
        }
        Map<String, List<SourceRef>> byExactName = new LinkedHashMap<>();
        for (SourceRef ref : ordered) {
            byExactName.computeIfAbsent(ref.fileName(), k -> new ArrayList<>()).add(ref);
        }

        List<String> allNames = ordered.stream().map(SourceRef::fileName).toList();

        Map<ProjectSourceId, DisplayLabel> out = new LinkedHashMap<>();
        int index = 0;
        for (SourceRef ref : ordered) {
            String alias = aliasForIndex(index++);
            String fullPath = ref.path() == null
                    ? ref.fileName()
                    : ref.path().toAbsolutePath().normalize().toString();
            List<SourceRef> sameName = byExactName.getOrDefault(ref.fileName(), List.of());
            String distinguishing;
            if (sameName.size() > 1) {
                // Identical filenames stay identical in the label; alias + path tooltip distinguish.
                distinguishing = ref.fileName();
            } else {
                List<String> peers = peerCluster(ref.fileName(), allNames);
                int commonPrefix = longestCommonPrefixLength(peers);
                int commonSuffix = longestCommonSuffixLength(peers);
                distinguishing = compactDistinguishing(
                        ref.fileName(), commonPrefix, commonSuffix, peers.size());
            }
            out.put(ref.sourceId(), new DisplayLabel(
                    ref.sourceId(),
                    alias,
                    distinguishing,
                    ref.fileName(),
                    fullPath
            ));
        }
        return Map.copyOf(out);
    }

    /**
     * Peer cluster: filenames that share a long enough prefix with {@code fileName}.
     * Prevents an unrelated short name from disabling abbreviation of a similar pair.
     */
    static List<String> peerCluster(String fileName, List<String> allNames) {
        if (fileName == null || allNames == null || allNames.isEmpty()) {
            return List.of();
        }
        final int minShared = 12;
        List<String> peers = new ArrayList<>();
        peers.add(fileName);
        for (String other : allNames) {
            if (other == null || other.equals(fileName)) {
                continue;
            }
            int shared = longestCommonPrefixLength(List.of(fileName, other));
            if (shared >= minShared) {
                peers.add(other);
            }
        }
        return List.copyOf(peers);
    }

    public static String aliasForIndex(int zeroBasedIndex) {
        if (zeroBasedIndex < 0) {
            return "Source ?";
        }
        if (zeroBasedIndex < 26) {
            return "Source " + (char) ('A' + zeroBasedIndex);
        }
        return "Source " + (zeroBasedIndex + 1);
    }

    /**
     * User-facing source identity. The measurement filename is primary; Source A/B/C
     * aliases stay internal diagnostic labels.
     */
    public static String primaryIdentity(DisplayLabel label) {
        if (label == null) {
            return "Measurement source";
        }
        String name = label.fullFileName();
        if (name == null || name.isBlank()) {
            name = label.distinguishingName();
        }
        if (name == null || name.isBlank()) {
            return "Measurement source";
        }
        return name;
    }

    public static String identityTooltip(DisplayLabel label) {
        if (label == null) {
            return "";
        }
        String name = primaryIdentity(label);
        String path = label.fullPath();
        if (path == null || path.isBlank() || path.equals(name)) {
            return name;
        }
        return name + "\n" + path;
    }

    static String compactDistinguishing(String fileName, int commonPrefix, int commonSuffix, int nameCount) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        if (nameCount <= 1) {
            return fileName.length() > 42 ? middleTruncate(fileName, 36) : fileName;
        }
        int len = fileName.length();
        int usablePrefix = Math.min(commonPrefix, len);
        usablePrefix = backtrackToSeparator(fileName, usablePrefix);
        int usableSuffix = Math.min(commonSuffix, len - usablePrefix);
        boolean abbreviate = usablePrefix >= 12 && usablePrefix + usableSuffix < len;
        if (!abbreviate) {
            return fileName.length() > 42 ? middleTruncate(fileName, 36) : fileName;
        }
        String unique = fileName.substring(usablePrefix, len - usableSuffix);
        String suffix = usableSuffix > 0 ? fileName.substring(len - usableSuffix) : "";
        if (unique.isBlank()) {
            return fileName;
        }
        String lead = "…";
        if (usablePrefix > 0) {
            char before = fileName.charAt(usablePrefix - 1);
            if (before == '_' || before == '-' || before == '.') {
                lead = "…" + before;
            }
        }
        return lead + unique + suffix;
    }

    /** Last-resort compact form: keep leading hint + trailing identifier + extension. */
    static String middleTruncate(String fileName, int maxLen) {
        if (fileName == null || fileName.length() <= maxLen) {
            return fileName == null ? "" : fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot) : "";
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        int unders = base.lastIndexOf('_');
        String tail = (unders >= 0 ? base.substring(unders) : base.substring(Math.max(0, base.length() - 8)))
                + ext;
        if (tail.length() + 2 >= maxLen) {
            int keep = Math.max(ext.length() + 1, Math.min(maxLen - 1, fileName.length()));
            return "…" + fileName.substring(fileName.length() - keep);
        }
        int keepStart = Math.max(4, maxLen - tail.length() - 1);
        if (keepStart + 1 + tail.length() > fileName.length()) {
            return fileName;
        }
        return fileName.substring(0, keepStart) + "…" + tail;
    }

    static int backtrackToSeparator(String fileName, int prefixLen) {
        int p = Math.max(0, Math.min(prefixLen, fileName.length()));
        while (p > 0) {
            char c = fileName.charAt(p - 1);
            if (c == '_' || c == '-' || c == '.' || c == ' ' || c == '/' || c == '\\') {
                return p;
            }
            p--;
        }
        return 0;
    }

    static int longestCommonPrefixLength(List<String> names) {
        if (names == null || names.isEmpty()) {
            return 0;
        }
        String first = names.getFirst();
        if (first == null) {
            return 0;
        }
        int end = first.length();
        for (int i = 1; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null) {
                return 0;
            }
            int lim = Math.min(end, n.length());
            int j = 0;
            while (j < lim && first.charAt(j) == n.charAt(j)) {
                j++;
            }
            end = j;
            if (end == 0) {
                return 0;
            }
        }
        return end;
    }

    static int longestCommonSuffixLength(List<String> names) {
        if (names == null || names.isEmpty()) {
            return 0;
        }
        String first = names.getFirst();
        if (first == null) {
            return 0;
        }
        int end = first.length();
        for (int i = 1; i < names.size(); i++) {
            String n = names.get(i);
            if (n == null) {
                return 0;
            }
            int lim = Math.min(end, n.length());
            int j = 0;
            while (j < lim
                    && first.charAt(first.length() - 1 - j) == n.charAt(n.length() - 1 - j)) {
                j++;
            }
            end = j;
            if (end == 0) {
                return 0;
            }
        }
        // Avoid treating the entire shorter name as "suffix" when names are unequal.
        int minLen = names.stream().filter(Objects::nonNull).mapToInt(String::length).min().orElse(0);
        return Math.min(end, Math.max(0, minLen - 1));
    }

    /** Compact frame count for headers (exact value remains available via tooltip). */
    public static String compactFrameCount(long frames) {
        if (frames < 10_000) {
            return String.format(Locale.US, "%,d", frames);
        }
        if (frames < 1_000_000L) {
            double k = frames / 1_000.0;
            return String.format(Locale.US, k >= 100 ? "%.0fK" : "%.1fK", k);
        }
        double m = frames / 1_000_000.0;
        return String.format(Locale.US, m >= 100 ? "%.0fM" : "%.2fM", m);
    }
}

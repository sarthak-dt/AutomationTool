package com.stellar.platform.api.nft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Records NFT measurements as JSON for baseline comparison across builds.
 * Does not invent hard pass/fail targets; callers decide soft thresholds.
 */
public final class NftBaselineReport {

    private final Map<String, Object> metrics = new LinkedHashMap<>();
    private final Instant createdAt = Instant.now();

    public NftBaselineReport put(String name, Object value) {
        metrics.put(Objects.requireNonNull(name, "name"), value);
        return this;
    }

    public Map<String, Object> metrics() {
        return Map.copyOf(metrics);
    }

    public void writeJson(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("createdAt", createdAt.toString());
        root.put("javaVersion", System.getProperty("java.version", ""));
        root.put("osName", System.getProperty("os.name", ""));
        root.put("metrics", metrics);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(path.toFile(), root);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMetrics(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> root = mapper.readValue(path.toFile(), Map.class);
        Object metrics = root.get("metrics");
        if (!(metrics instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    /**
     * Soft regression check: current must be &lt;= baseline * factor (duration/size metrics).
     * Returns empty when baseline metric missing (first run).
     */
    public static String compareSoft(
            Map<String, Object> baseline,
            Map<String, Object> current,
            String metric,
            double factor
    ) {
        if (baseline == null || current == null || !baseline.containsKey(metric) || !current.containsKey(metric)) {
            return "";
        }
        double base = toDouble(baseline.get(metric));
        double cur = toDouble(current.get(metric));
        if (base <= 0.0) {
            return "";
        }
        if (cur > base * factor) {
            return metric + " regressed: current=" + cur + " baseline=" + base + " factor=" + factor;
        }
        return "";
    }

    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (RuntimeException e) {
            return 0.0;
        }
    }
}

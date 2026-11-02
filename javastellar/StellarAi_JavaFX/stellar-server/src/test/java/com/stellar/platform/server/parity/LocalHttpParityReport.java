package com.stellar.platform.server.parity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * STEP 6 quality-gate report: LOCAL / HTTP PARITY categories.
 * Critical category failures prevent overall success.
 */
public final class LocalHttpParityReport {

    public enum Category {
        PLAN,
        JOBS,
        DIAGNOSTICS,
        CATALOG,
        METADATA,
        TIMESTAMPS,
        VALUES,
        RANGE_QUERIES
    }

    private final Map<Category, List<String>> failures = new EnumMap<>(Category.class);
    private final Map<Category, Integer> checks = new EnumMap<>(Category.class);
    private final List<String> scenarios = new ArrayList<>();

    public LocalHttpParityReport() {
        for (Category c : Category.values()) {
            failures.put(c, new ArrayList<>());
            checks.put(c, 0);
        }
    }

    public void scenario(String name) {
        scenarios.add(name);
    }

    public void check(Category category, boolean pass, String detail) {
        checks.put(category, checks.get(category) + 1);
        if (!pass) {
            failures.get(category).add(detail);
        }
    }

    public void fail(Category category, String detail) {
        check(category, false, detail);
    }

    public boolean categoryPassed(Category category) {
        return failures.get(category).isEmpty();
    }

    public boolean allPassed() {
        for (Category c : Category.values()) {
            if (!categoryPassed(c)) {
                return false;
            }
        }
        return true;
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("LOCAL / HTTP PARITY\n");
        sb.append("====================\n");
        if (!scenarios.isEmpty()) {
            sb.append("Scenarios: ").append(String.join(", ", scenarios)).append('\n');
        }
        for (Category c : Category.values()) {
            String label = switch (c) {
                case PLAN -> "Plan";
                case JOBS -> "Jobs";
                case DIAGNOSTICS -> "Diagnostics";
                case CATALOG -> "Catalog";
                case METADATA -> "Metadata";
                case TIMESTAMPS -> "Timestamps";
                case VALUES -> "Values";
                case RANGE_QUERIES -> "Range queries";
            };
            String status = categoryPassed(c) ? "PASS" : "FAIL";
            sb.append(String.format("%-16s %s  (%d checks)\n", label, status, checks.get(c)));
            for (String f : failures.get(c)) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        sb.append("--------------------\n");
        sb.append("LOCAL == HTTP: ").append(allPassed() ? "YES" : "NO").append('\n');
        return sb.toString();
    }
}

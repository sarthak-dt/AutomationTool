package com.stellar.platform.analysis.rules;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic role → signal binding. Never silently picks among candidates.
 */
public final class BindingResolver {

    private BindingResolver() {
    }

    public static Map<String, SignalBinding> resolve(
            DiagnosticRuleDefinition rule,
            List<SignalCatalogEntry> catalog,
            Map<String, SignalCatalogKey> explicit
    ) {
        Objects.requireNonNull(rule, "rule");
        List<SignalCatalogEntry> entries = catalog == null ? List.of() : catalog;
        Map<String, SignalCatalogKey> overrides = explicit == null ? Map.of() : explicit;
        Map<String, SignalBinding> out = new LinkedHashMap<>();
        for (RuleInput input : rule.inputs()) {
            out.put(input.role(), bindOne(input, entries, overrides.get(input.role())));
        }
        return Map.copyOf(out);
    }

    private static SignalBinding bindOne(
            RuleInput input,
            List<SignalCatalogEntry> catalog,
            SignalCatalogKey explicit
    ) {
        if (explicit != null) {
            SignalCatalogEntry match = catalog.stream()
                    .filter(e -> e.key().equals(explicit))
                    .findFirst()
                    .orElse(null);
            String unit = match == null ? "" : match.unit();
            if (match == null) {
                return new SignalBinding(input.role(), BindingState.STALE, explicit, "",
                        "Explicit binding not present in catalog: " + input.role());
            }
            return checkUnit(input, explicit, unit);
        }
        List<SignalCatalogEntry> candidates = new ArrayList<>();
        for (SignalCatalogEntry entry : catalog) {
            if (nameMatches(entry.key().signalName(), input)) {
                candidates.add(entry);
            }
        }
        if (candidates.isEmpty()) {
            return input.required() ? SignalBinding.missing(input.role())
                    : new SignalBinding(input.role(), BindingState.MISSING, null, "", "Optional input absent");
        }
        if (candidates.size() > 1) {
            return SignalBinding.ambiguous(input.role(), candidates.size());
        }
        SignalCatalogEntry only = candidates.getFirst();
        return checkUnit(input, only.key(), only.unit());
    }

    private static SignalBinding checkUnit(RuleInput input, SignalCatalogKey key, String signalUnit) {
        String expected = input.expectedUnit();
        if (expected.isBlank() && (signalUnit == null || signalUnit.isBlank())) {
            return new SignalBinding(input.role(), BindingState.BOUND, key, "", "");
        }
        if (expected.isBlank()) {
            return new SignalBinding(input.role(), BindingState.BOUND, key, signalUnit, "");
        }
        if (signalUnit == null || signalUnit.isBlank()) {
            return new SignalBinding(input.role(), BindingState.INCOMPATIBLE_UNIT, key, "",
                    "Signal unit missing; rule expects " + expected);
        }
        if (!UnitRegistry.compatible(expected, signalUnit)) {
            return new SignalBinding(input.role(), BindingState.INCOMPATIBLE_UNIT, key, signalUnit,
                    "Incompatible units: rule " + expected + " vs signal " + signalUnit);
        }
        return new SignalBinding(input.role(), BindingState.BOUND, key, signalUnit, "");
    }

    private static boolean nameMatches(String signalName, RuleInput input) {
        if (signalName == null) {
            return false;
        }
        String n = signalName.trim().toLowerCase(Locale.ROOT);
        if (n.equals(input.role().toLowerCase(Locale.ROOT))) {
            return true;
        }
        for (String alias : input.aliases()) {
            if (alias != null && n.equals(alias.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

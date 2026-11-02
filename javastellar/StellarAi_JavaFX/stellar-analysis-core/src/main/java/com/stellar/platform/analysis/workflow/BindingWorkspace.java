package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.rules.BindingResolver;
import com.stellar.platform.analysis.rules.BindingState;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.RuleInput;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared project/pack role bindings. One bind applies to every rule using the role.
 * Never auto-selects among ambiguous candidates.
 */
public final class BindingWorkspace {

    private BindingWorkspace() {
    }

    public static List<RuleInput> uniqueRoles(DiagnosticRulePack pack) {
        Map<String, RuleInput> roles = new LinkedHashMap<>();
        if (pack == null) {
            return List.of();
        }
        for (DiagnosticRuleDefinition rule : pack.rules()) {
            for (RuleInput input : rule.inputs()) {
                roles.putIfAbsent(input.role(), input);
            }
        }
        return List.copyOf(roles.values());
    }

    public static Map<String, SignalBinding> resolveShared(
            DiagnosticRulePack pack,
            List<SignalCatalogEntry> catalog,
            Map<String, SignalCatalogKey> explicit
    ) {
        Map<String, SignalBinding> out = new LinkedHashMap<>();
        for (RuleInput input : uniqueRoles(pack)) {
            Map<String, SignalBinding> one = BindingResolver.resolve(
                    stub(input), catalog, explicit);
            out.put(input.role(), one.get(input.role()));
        }
        return Map.copyOf(out);
    }

    public static List<BindingCandidate> candidates(
            RuleInput input,
            List<SignalCatalogEntry> catalog
    ) {
        List<BindingCandidate> out = new ArrayList<>();
        if (input == null || catalog == null) {
            return List.of();
        }
        for (SignalCatalogEntry entry : catalog) {
            if (nameMatches(entry.key().signalName(), input)) {
                out.add(BindingCandidate.of(entry));
            }
        }
        return List.copyOf(out);
    }

    public static Set<String> unresolvedRoles(Map<String, SignalBinding> bindings) {
        Set<String> out = new LinkedHashSet<>();
        if (bindings == null) {
            return Set.of();
        }
        bindings.forEach((role, b) -> {
            if (b == null || !b.bound()) {
                out.add(role);
            }
        });
        return Set.copyOf(out);
    }

    public static boolean stale(SignalBinding binding) {
        return binding != null && binding.state() == BindingState.STALE;
    }

    private static DiagnosticRuleDefinition stub(RuleInput input) {
        return new DiagnosticRuleDefinition(
                "binding." + input.role(), "1", input.role(), "",
                com.stellar.platform.analysis.rules.RuleCategory.CUSTOM,
                com.stellar.platform.analysis.model.AnalysisSeverity.INFO, true,
                List.of(input), List.of(),
                new com.stellar.platform.analysis.rules.RuleCondition.StateEquals(input.role(), "", false),
                List.of(), List.of(),
                com.stellar.platform.analysis.rules.RuleOrigin.PROJECT_CUSTOM, null, null);
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

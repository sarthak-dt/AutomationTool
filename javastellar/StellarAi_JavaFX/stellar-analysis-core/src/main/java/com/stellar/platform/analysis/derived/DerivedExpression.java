package com.stellar.platform.analysis.derived;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Typed expression. Not a script. Not reflection. Not eval.
 */
public sealed interface DerivedExpression {

    record Add(SignalCatalogKey a, SignalCatalogKey b) implements DerivedExpression {}
    record Subtract(SignalCatalogKey a, SignalCatalogKey b) implements DerivedExpression {}
    record Scale(SignalCatalogKey a, double constant, boolean divide) implements DerivedExpression {}
    record Abs(SignalCatalogKey a) implements DerivedExpression {}
    record Delta(SignalCatalogKey a) implements DerivedExpression {}
    record Rate(SignalCatalogKey a) implements DerivedExpression {}
    record Min(SignalCatalogKey a, SignalCatalogKey b) implements DerivedExpression {}
    record Max(SignalCatalogKey a, SignalCatalogKey b) implements DerivedExpression {}

    default List<SignalCatalogKey> inputs() {
        return switch (this) {
            case Add e -> List.of(e.a(), e.b());
            case Subtract e -> List.of(e.a(), e.b());
            case Scale e -> List.of(e.a());
            case Abs e -> List.of(e.a());
            case Delta e -> List.of(e.a());
            case Rate e -> List.of(e.a());
            case Min e -> List.of(e.a(), e.b());
            case Max e -> List.of(e.a(), e.b());
        };
    }

    default String canonical() {
        return switch (this) {
            case Add e -> "add(" + id(e.a()) + "," + id(e.b()) + ")";
            case Subtract e -> "sub(" + id(e.a()) + "," + id(e.b()) + ")";
            case Scale e -> (e.divide() ? "div" : "mul") + "(" + id(e.a()) + "," + e.constant() + ")";
            case Abs e -> "abs(" + id(e.a()) + ")";
            case Delta e -> "delta(" + id(e.a()) + ")";
            case Rate e -> "rate(" + id(e.a()) + ")";
            case Min e -> "min(" + id(e.a()) + "," + id(e.b()) + ")";
            case Max e -> "max(" + id(e.a()) + "," + id(e.b()) + ")";
        };
    }

    static DerivedExpression parse(String op, SignalCatalogKey a, SignalCatalogKey b, Double constant) {
        String kind = op == null ? "" : op.trim().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "add", "a+b" -> new Add(require(a), require(b));
            case "sub", "a-b", "subtract" -> new Subtract(require(a), require(b));
            case "mul", "a*k" -> new Scale(require(a), constant == null ? 1d : constant, false);
            case "div", "a/k" -> new Scale(require(a), constant == null ? 1d : constant, true);
            case "abs" -> new Abs(require(a));
            case "delta" -> new Delta(require(a));
            case "rate" -> new Rate(require(a));
            case "min" -> new Min(require(a), require(b));
            case "max" -> new Max(require(a), require(b));
            default -> throw new IllegalArgumentException("Unsupported derived operation: " + op);
        };
    }

    private static SignalCatalogKey require(SignalCatalogKey key) {
        return Objects.requireNonNull(key, "signal");
    }

    private static String id(SignalCatalogKey key) {
        return key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                + "|" + key.canId() + "|" + key.channel()
                + "|" + key.sourceFile() + "|" + key.sourceDatabase();
    }
}
